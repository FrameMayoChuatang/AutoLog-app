package com.framemayo.autolog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.framemayo.autolog.R
import com.framemayo.autolog.container
import com.framemayo.autolog.vehicle.ActiveTrip
import com.framemayo.autolog.vehicle.DetectedEnergyEvent
import com.framemayo.autolog.vehicle.EnergyEventDetector
import com.framemayo.autolog.vehicle.MotionAction
import com.framemayo.autolog.vehicle.MotionTracker
import com.framemayo.autolog.vehicle.OdometerFilter
import com.framemayo.autolog.vehicle.OdometerValidator
import com.framemayo.autolog.vehicle.ResumedStop
import com.framemayo.autolog.vehicle.gearWithCatchUp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 앱 A 수집 서비스: 누적거리·기어·연료/배터리 수집, 1초 저장, 강제 종료 복구(#40), SDK 재시도(#89),
 * 정차 판단·중간 정차·종료 안내(#90), 이동 감지 자동 시작(#91), 주유·충전 감지(#92).
 */
class TripRecorderService : LifecycleService() {

    private val recovery by lazy { TripRecovery(container.trips) }
    private val motion = MotionTracker()
    private val motionLock = Mutex()
    private val energyDetector = EnergyEventDetector()
    /** 실시간 누적거리 이상값 필터(#141). 수집 코루틴에서만 쓴다. */
    private val odometerFilter = OdometerFilter()
    private val autoStart by lazy {
        AutoTripController(
            container.trips,
            energy = { RecorderState.snapshot.value.energyLevels() },
            onError = { Log.w(TAG, "auto start failed", it) },
        )
    }

    @Volatile private var batteryCapacityKwh: Float? = null
    /** 진행 중 운행이 있을 때만 1초 저장을 한다(#147). */
    @Volatile private var hasActiveTrip = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        RecorderState.update { it.copy(serviceRunning = true, collector = CollectorStatus.STARTING) }

        val vehicle = container.vehicle
        val trips = container.trips

        lifecycleScope.launch(uncaught) {
            val init = async {
                retryWithBackoff(onRetry = { n, e, ms ->
                    Log.w(TAG, "Vehicle SDK initialize failed (#$n), retry in ${ms}ms", e)
                    setCollector(CollectorStatus.RETRYING)
                }) { vehicle.initialize() }
            }
            // 초기화가 늦어도 남은 ACTIVE 운행을 붙잡아 두지 않는다. 못 읽으면 첫 유효 누적거리로 늦게 판단한다.
            val km = withTimeoutOrNull(RECOVERY_WAIT_MS) { init.await(); vehicle.currentOdometerKm() }
            val readAt = now()
            val startup = guard("startup")
            startup.run { onRecovered(recovery.recover(km, readAt), "recovery") }
            // 복구 때 읽은 실측값을 먼저 반영해, 구독 직후 늦게 도착하는 이전(더 작은) 값을 감소값으로 걸러 낸다.
            // 이 값이 없으면 오래된 값이 자동 시작 기준점이 되어 앱이 꺼진 동안의 주행이 새 운행에 붙는다.
            if (OdometerValidator.isValid(km, null)) {
                odometerFilter.seed(km, readAt)
                RecorderState.update { it.copy(odometerKm = km, odometerUpdatedAt = readAt) }
            }

            init.await()
            setCollector(CollectorStatus.RUNNING)
            batteryCapacityKwh = withTimeoutOrNull(RECOVERY_WAIT_MS) { vehicle.batteryCapacityKwh() }

            // 자동 시작 기준값은 복구가 끝난 뒤 정한다(남은 ACTIVE 운행을 새 운행으로 오인하지 않도록).
            startup.run { autoStart.seed() }
            // 한 수집기가 끝나도 형제 수집기는 계속 돈다(#139).
            supervisorScope {
                launch {
                    val g = guard("active trip")
                    trips.active.collect {
                        hasActiveTrip = it != null
                        g.run { autoStart.onActiveTrip(it) }
                    }
                }
                launch { observeActiveTripForStops() }
                launch { collectOdometer() }
                launch { collectGear() }
                launch {
                    collectEnergy(vehicle.fuelPercent(), "fuel") { p, t ->
                        RecorderState.update { it.copy(fuelPercent = p) }
                        energyDetector.onFuel(p, t)
                    }
                }
                launch {
                    collectEnergy(vehicle.batteryPercent(), "battery") { p, t ->
                        RecorderState.update { it.copy(batteryPercent = p) }
                        energyDetector.onBattery(p, t)
                    }
                }
                launch {
                    collectEnergy(vehicle.chargePortOpen(), "charge port") { open, t ->
                        RecorderState.update { it.copy(chargePortOpen = open) }
                        energyDetector.onChargePort(open, t)
                    }
                }
                launch { tickLoop() }
            }
        }
    }

    /**
     * 복구로 끝난 운행은 정차 추적기가 보기 전에 끝나므로, 열려 있던 주차 구간을 운행 종료 시각으로 닫는다.
     * (닫지 않으면 끝난 운행에 종료 시각 없는 정차가 남는다.)
     */
    private suspend fun onRecovered(result: RecoveryResult, label: String) {
        val trip = when (result) {
            is RecoveryResult.Completed -> result.trip
            is RecoveryResult.Interrupted -> result.trip
            is RecoveryResult.Resumed -> result.trip
            is RecoveryResult.Deferred -> result.trip
            RecoveryResult.None -> null
        }
        Log.i(TAG, "$label: ${result::class.simpleName} trip=${trip?.id}")
        if (trip == null || result is RecoveryResult.Resumed || result is RecoveryResult.Deferred) return
        container.stops.close(trip.id, trip.endedAt ?: trip.lastSavedAt)
    }

    private suspend fun collectOdometer() {
        val vehicle = container.vehicle
        vehicle.odometerKm().resubscribing(
            onFailure = { n, e, ms ->
                Log.w(TAG, "odometer resubscribe #$n in ${ms}ms", e)
                setCollector(CollectorStatus.RETRYING)
            },
            prime = { vehicle.currentOdometerKm() },
            onRecovered = { setCollector(CollectorStatus.RUNNING) },
        ).collectGuarded(guard("odometer")) { value ->
            val now = now()
            // 0·감소·튀는 값은 거른다. 튄 값이 기준이 돼도 일관된 정상 값이 이어지면 기준을 되돌린다(#141).
            val km = odometerFilter.accept(value, now) ?: return@collectGuarded
            if (recovery.awaitingOdometer) onRecovered(recovery.onFirstValidOdometer(km, now), "late recovery")
            RecorderState.update { it.copy(odometerKm = km, odometerUpdatedAt = now) }
            feedMotion(now) { onOdometer(km, now) }
            autoStart.onOdometer(km, now)?.let { Log.i(TAG, "auto trip started: ${it.id} from ${it.startOdometerKm}km") }
        }
    }

    private suspend fun collectGear() {
        val vehicle = container.vehicle
        gearWithCatchUp(vehicle.gear(), read = { vehicle.currentGear() }).resubscribing(
            onFailure = { n, e, ms ->
                Log.w(TAG, "gear resubscribe #$n in ${ms}ms", e)
                setCollector(CollectorStatus.RETRYING)
            },
            onRecovered = { setCollector(CollectorStatus.RUNNING) },
        ).collectGuarded(guard("gear")) { gear ->
            val now = now()
            if (RecorderState.snapshot.value.gear != gear) Log.i(TAG, "gear: $gear")
            RecorderState.update { it.copy(gear = gear) }
            feedMotion(now) { onGear(gear, now) }
            autoStart.onGear(gear, now)
        }
    }

    /** 연료·배터리·충전구는 부가 정보다. 미지원 차량은 길게 쉬며 재시도하고 수집 상태는 바꾸지 않는다. */
    private suspend fun <T> collectEnergy(source: Flow<T>, label: String, handle: (T, Long) -> List<DetectedEnergyEvent>) {
        source.resubscribing(
            maxMs = ENERGY_RETRY_MAX_MS,
            onFailure = { n, e, _ -> if (n == 1) Log.i(TAG, "$label unavailable: ${e.message}") },
        ).collectGuarded(guard(label)) { value -> recordEnergy(handle(value, now())) }
    }

    /** 운행 시작·종료·교체를 정차 추적에 알린다. 서비스 재시작 시 열린 정차 구간을 이어 받는다. */
    private suspend fun observeActiveTripForStops() {
        val stops = container.stops
        container.trips.active.map { it?.let { t -> t.id to t.startedAt } }.distinctUntilChanged().collectGuarded(guard("stops")) { trip ->
            val now = now()
            val active = trip?.let { (id, startedAt) ->
                ActiveTrip(id, startedAt, stops.openStop(id)?.let { ResumedStop(it.startedAt, it.odometerKm) })
            }
            feedMotion(now) { onActiveTrip(active, now) }
        }
    }

    private suspend fun feedMotion(now: Long, block: MotionTracker.() -> List<MotionAction>) {
        val (actions, status) = motionLock.withLock { motion.block() to motion.status }
        for (action in actions) when (action) {
            is MotionAction.OpenStop, is MotionAction.CloseStop -> container.stops.apply(action)
        }
        RecorderState.update {
            it.copy(
                motion = status.motion,
                endCandidate = status.endCandidate,
                stationaryMs = status.stationaryMs,
                stopCandidate = status.stopCandidate,
            )
        }
        val stationary = status.motion == MotionState.PARKED || status.motion == MotionState.IDLE
        recordEnergy(energyDetector.onMotion(stationary, now, RecorderState.snapshot.value.odometerKm))
    }

    /** 감지한 주유·충전을 그 시각에 진행 중이던 운행, 없으면 직전 운행에 연결한다. */
    private suspend fun recordEnergy(events: List<DetectedEnergyEvent>) {
        if (events.isEmpty()) return
        val trips = container.trips
        for (event in events) {
            val tripId = trips.active.first()?.takeIf { it.startedAt <= event.detectedAt }?.id ?: trips.lastEnded()?.id
            val saved = container.energy.record(event, tripId, batteryCapacityKwh)
            Log.i(TAG, "${saved.kind} detected ${saved.beforePercent}→${saved.afterPercent}% est ${saved.estimatedCostWon}원")
        }
    }

    private suspend fun tickLoop() {
        val g = guard("tick")
        while (lifecycleScope.isActive) {
            g.run { tick(now()) }
            delay(HEARTBEAT_MS)
        }
    }

    /** 1초 주기 작업: 보류 복구 확인, 운행 중 저장, 정차 판단, 주유·충전 확인. */
    private suspend fun tick(now: Long) {
        // SDK가 늦어 보류한 운행은 재개 창이 끝나면 중단 처리한다(#145).
        if (recovery.awaitingOdometer) recovery.onTick(now).takeIf { it != RecoveryResult.None }?.let { onRecovered(it, "recovery timeout") }
        if (hasActiveTrip) RecorderState.snapshot.value.odometerKm?.let { container.trips.heartbeat(it) }
        feedMotion(now) { onTick(now) }
        recordEnergy(energyDetector.onTick(now))
    }

    /** 흐름의 각 값을 [g]로 감싸 처리한다. 한 값 처리가 실패해도 다음 값을 계속 받는다. */
    private suspend fun <T> Flow<T>.collectGuarded(g: CycleGuard, block: suspend (T) -> Unit) =
        collect { value -> g.run { block(value) } }

    private fun setCollector(status: CollectorStatus) = RecorderState.update { it.copy(collector = status) }

    /** 한 주기가 실패해도(예: 저장 공간 부족으로 DB 쓰기 실패) 로그·상태만 남기고 다음 주기에 계속한다(#139). */
    private fun guard(label: String) = CycleGuard(
        onError = { e ->
            Log.e(TAG, "$label failed, continuing", e)
            setCollector(CollectorStatus.RETRYING)
        },
        onRecovered = {
            Log.i(TAG, "$label recovered")
            setCollector(CollectorStatus.RUNNING)
        },
    )

    /** 가드 밖에서 놓친 예외도 앱을 종료시키지 않도록 기록만 한다(#139). */
    private val uncaught = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "collector coroutine failed", e)
        setCollector(CollectorStatus.RETRYING)
    }

    private fun now() = System.currentTimeMillis()

    override fun onDestroy() {
        RecorderState.update { it.copy(serviceRunning = false, collector = CollectorStatus.STOPPED) }
        runCatching { container.vehicle.release() }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "운행 기록", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("운행 기록 준비 중")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "TripRecorderService"
        private const val CHANNEL = "trip_recorder"
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_MS = 1_000L
        private const val RECOVERY_WAIT_MS = 5_000L
        private const val ENERGY_RETRY_MAX_MS = 5 * 60_000L

        fun start(context: Context) {
            if (RecorderState.snapshot.value.serviceRunning) return
            context.startForegroundService(Intent(context, TripRecorderService::class.java))
        }
    }
}
