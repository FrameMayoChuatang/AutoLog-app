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
import com.framemayo.autolog.data.local.EnergyLevels
import com.framemayo.autolog.data.local.TripEndReason
import com.framemayo.autolog.vehicle.ActiveTrip
import com.framemayo.autolog.vehicle.DetectedEnergyEvent
import com.framemayo.autolog.vehicle.EnergyEventDetector
import com.framemayo.autolog.vehicle.MotionAction
import com.framemayo.autolog.vehicle.MotionTracker
import com.framemayo.autolog.vehicle.OdometerValidator
import com.framemayo.autolog.vehicle.ResumedStop
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
 * 정차 판단·중간 정차·장시간 주차 자동 종료(#90), 이동 감지 자동 시작(#91), 주유·충전 감지(#92).
 */
class TripRecorderService : LifecycleService() {

    private val recovery by lazy { TripRecovery(container.trips) }
    private val motion = MotionTracker()
    private val motionLock = Mutex()
    private val energyDetector = EnergyEventDetector()
    private val autoStart by lazy {
        AutoTripController(
            container.trips,
            energy = { RecorderState.snapshot.value.energyLevels() },
            onError = { Log.w(TAG, "auto start failed", it) },
        )
    }

    /** 주차 시작 시점의 연료·배터리. 장시간 주차 자동 종료는 주차 시작 시각으로 끝나므로 그때 값을 쓴다. */
    @Volatile private var parkEnergy: EnergyLevels? = null
    @Volatile private var batteryCapacityKwh: Float? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        RecorderState.update { it.copy(serviceRunning = true, collector = CollectorStatus.STARTING) }

        val vehicle = container.vehicle
        val trips = container.trips

        lifecycleScope.launch {
            val init = async {
                retryWithBackoff(onRetry = { n, e, ms ->
                    Log.w(TAG, "Vehicle SDK initialize failed (#$n), retry in ${ms}ms", e)
                    setCollector(CollectorStatus.RETRYING)
                }) { vehicle.initialize() }
            }
            // 초기화가 늦어도 남은 ACTIVE 운행을 붙잡아 두지 않는다. 못 읽으면 첫 유효 누적거리로 늦게 판단한다.
            val km = withTimeoutOrNull(RECOVERY_WAIT_MS) { init.await(); vehicle.currentOdometerKm() }
            Log.i(TAG, "recovery: ${recovery.recover(km, now())}")

            init.await()
            setCollector(CollectorStatus.RUNNING)
            batteryCapacityKwh = withTimeoutOrNull(RECOVERY_WAIT_MS) { vehicle.batteryCapacityKwh() }

            // 자동 시작 기준값은 복구가 끝난 뒤 정한다(남은 ACTIVE 운행을 새 운행으로 오인하지 않도록).
            autoStart.seed()
            launch { trips.active.collect { autoStart.onActiveTrip(it) } }
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

    private suspend fun collectOdometer() {
        val vehicle = container.vehicle
        vehicle.odometerKm().resubscribing(
            onFailure = { n, e, ms ->
                Log.w(TAG, "odometer resubscribe #$n in ${ms}ms", e)
                setCollector(CollectorStatus.RETRYING)
            },
            prime = { vehicle.currentOdometerKm() },
            onRecovered = { setCollector(CollectorStatus.RUNNING) },
        ).collect { value ->
            val now = now()
            if (!OdometerValidator.isValid(value, RecorderState.snapshot.value.odometerKm)) return@collect
            val km = value!!
            if (recovery.awaitingOdometer) Log.i(TAG, "late recovery: ${recovery.onFirstValidOdometer(km, now)}")
            RecorderState.update { it.copy(odometerKm = km, odometerUpdatedAt = now) }
            feedMotion(now) { onOdometer(km, now) }
            autoStart.onOdometer(km, now)?.let { Log.i(TAG, "auto trip started: ${it.id} from ${it.startOdometerKm}km") }
        }
    }

    private suspend fun collectGear() {
        container.vehicle.gear().resubscribing(
            onFailure = { n, e, ms ->
                Log.w(TAG, "gear resubscribe #$n in ${ms}ms", e)
                setCollector(CollectorStatus.RETRYING)
            },
            onRecovered = { setCollector(CollectorStatus.RUNNING) },
        ).collect { gear ->
            val now = now()
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
        ).collect { value -> recordEnergy(handle(value, now())) }
    }

    /** 운행 시작·종료·교체를 정차 추적에 알린다. 서비스 재시작 시 열린 정차 구간을 이어 받는다. */
    private suspend fun observeActiveTripForStops() {
        val stops = container.stops
        container.trips.active.map { it?.let { t -> t.id to t.startedAt } }.distinctUntilChanged().collect { trip ->
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
            is MotionAction.OpenStop -> {
                parkEnergy = RecorderState.snapshot.value.energyLevels()
                container.stops.apply(action)
            }
            is MotionAction.CloseStop -> container.stops.apply(action)
            is MotionAction.AutoEnd -> autoEnd(action)
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

    /** 장시간 주차: 주차 시작 시점의 실측 시각·누적거리로 종료한다. 그 사이 다른 운행으로 바뀌었으면 건드리지 않는다. */
    private suspend fun autoEnd(action: MotionAction.AutoEnd) {
        val trips = container.trips
        if (trips.active.first()?.id != action.tripId) return
        container.stops.close(action.tripId, action.endedAt)
        val ended = trips.end(action.odometerKm, action.endedAt, TripEndReason.AUTO_PARKED, parkEnergy) ?: return
        Log.i(TAG, "trip ${ended.id} auto-ended after long park at ${action.odometerKm}km")
        autoStart.onTripEnded(ended)
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
        while (lifecycleScope.isActive) {
            val now = now()
            RecorderState.snapshot.value.odometerKm?.let { container.trips.heartbeat(it) }
            feedMotion(now) { onTick(now) }
            recordEnergy(energyDetector.onTick(now))
            delay(HEARTBEAT_MS)
        }
    }

    private fun setCollector(status: CollectorStatus) = RecorderState.update { it.copy(collector = status) }

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
