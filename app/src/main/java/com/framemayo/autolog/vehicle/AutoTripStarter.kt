package com.framemayo.autolog.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripStatus

/**
 * 운행 자동 시작 판단기(#91). 안드로이드·저장소에 의존하지 않는 순수 상태 기계이며 스레드 안전하지 않다.
 * 동시 호출 보호와 실제 운행 생성은 [com.framemayo.autolog.service.AutoTripController]가 맡는다.
 *
 * 규칙
 * - 기준점(baseline): 진행 중 운행이 없을 때의 마지막 정지 누적거리와 그 시각. 모두 실측값이다.
 *   - 운행이 없고 기어가 P면 유효한 누적거리가 올 때마다 기준점을 갱신한다.
 *   - 운행이 끝나면(수동·자동) 그 운행의 종료 누적거리·종료 시각이 기준점이 된다.
 *   - 서비스 시작 시 [seed]로 마지막 완료 운행의 종료값을 기준점으로 삼는다. 없으면 첫 유효 누적거리가 기준점이 된다.
 *   - INTERRUPTED 운행은 종료값이 실측이 아니므로 기준점으로 쓰지 않고 다음 유효 누적거리로 다시 잡는다.
 * - 운행이 없고 기어가 P가 아니며 누적거리가 기준점 + [epsilonKm] 이상이면 시작을 결정한다.
 *   시작 누적거리는 기준점 누적거리, 시작 시각은 P 해제 시각(관측된 경우)과 기준점 시각 중 늦은 쪽이다.
 *   서비스가 처음 본 기어가 이미 P가 아니면 그 관측 시각을 해제 시각으로 본다. 어젯밤 운행 종료 시각부터
 *   밤새 이어진 운행이 만들어지는 것을 막기 위함이며, 실제 해제 시각보다 늦을 수는 있어도 이르지는 않다.
 * - 기어 null(미확인)은 "P로 확인되지 않음"으로 본다. 기어만으로는 시작하지 않지만, 누적거리 증가가
 *   이동의 강한 증거이므로 null이어도 누적거리가 임계값을 넘으면 시작한다. 기어 구독이 실패한 차량에서도 기록을 잃지 않기 위함.
 * - 기준점보다 작은 누적거리가 오면(잘못 시드된 기준점 등) 그 실측값으로 기준점을 낮춘다.
 * - [seed]로 잡은 기준점과 첫 실측값의 차이가 [maxSeedGapKm]를 넘으면 앱이 꺼진 동안 기록되지 않은 주행이 있었던 것이므로
 *   오래된 기준점을 버리고 그 실측값을 새 기준점으로 삼는다(며칠 전 시각으로 운행이 시작되는 것을 막는다).
 */
class AutoTripStarter(
    private val epsilonKm: Float = DEFAULT_EPSILON_KM,
    private val maxSeedGapKm: Float = DEFAULT_MAX_SEED_GAP_KM,
) {
    data class StartTrip(val startKm: Float, val startedAt: Long)

    private var baselineKm: Float? = null
    private var baselineAt: Long = 0L
    private var baselineFromSeed = false

    private var gear: VehicleGear? = null
    private var lastKnownGear: VehicleGear? = null
    private var parkReleasedAt: Long? = null

    /** 진행 중이거나 시작 결정을 내려 생성 대기 중인 운행이 있으면 true. */
    var tripActive: Boolean = false
        private set

    val baseline: Pair<Float, Long>? get() = baselineKm?.let { it to baselineAt }

    /** 서비스 시작 시 1회. 이미 실측으로 기준점이 잡혔다면 기준점은 덮어쓰지 않는다. */
    fun seed(lastEnded: TripEntity?, activeTrip: TripEntity? = null) {
        if (activeTrip != null) tripActive = true
        if (baselineKm != null || lastEnded == null) return
        val km = lastEnded.endOdometerKm ?: return
        if (lastEnded.status != TripStatus.COMPLETED) return
        baselineKm = km
        baselineAt = lastEnded.endedAt ?: lastEnded.lastSavedAt
        baselineFromSeed = true
    }

    fun onGear(gear: VehicleGear?, now: Long) {
        this.gear = gear
        if (gear == null) return
        if (gear == VehicleGear.GEAR_PARK) {
            parkReleasedAt = null
        } else if (lastKnownGear == null || lastKnownGear == VehicleGear.GEAR_PARK) {
            parkReleasedAt = now
        }
        lastKnownGear = gear
    }

    /** [km]는 [OdometerValidator]를 통과한 실측값이어야 한다. 시작해야 하면 결정을 돌려주고 [tripActive]를 true로 둔다. */
    fun onOdometer(km: Float, now: Long): StartTrip? {
        if (tripActive) return null
        val base = baselineKm
        val fromSeed = baselineFromSeed
        baselineFromSeed = false
        if (base == null || km < base || gear == VehicleGear.GEAR_PARK ||
            (fromSeed && km.toDouble() - base.toDouble() > maxSeedGapKm)
        ) {
            setBaseline(km, now)
            return null
        }
        if (km.toDouble() - base.toDouble() < epsilonKm - FLOAT_TOLERANCE) return null
        tripActive = true
        return StartTrip(startKm = base, startedAt = maxOf(baselineAt, parkReleasedAt ?: baselineAt))
    }

    /** 운행이 진행 중임을 알게 됐을 때(수동 시작 포함). */
    fun onTripActive() {
        tripActive = true
    }

    /** 시작 결정 후 운행 생성이 실패했을 때. 기준점은 그대로라 다음 누적거리에서 다시 판단한다. */
    fun onStartFailed() {
        tripActive = false
    }

    /** 운행 종료. 완료 운행은 실측 종료값이 기준점이 되고, 종료값이 없으면 다음 유효 누적거리로 기준점을 다시 잡는다. */
    fun onTripEnded(trip: TripEntity?) {
        val km = trip?.endOdometerKm
        if (trip != null && km != null && trip.status == TripStatus.COMPLETED) {
            onTripEnded(km, trip.endedAt ?: trip.lastSavedAt)
        } else {
            tripActive = false
            baselineKm = null
            baselineFromSeed = false
        }
    }

    fun onTripEnded(endKm: Float, endedAt: Long) {
        tripActive = false
        setBaseline(endKm, endedAt)
    }

    private fun setBaseline(km: Float, at: Long) {
        baselineKm = km
        baselineAt = at
        baselineFromSeed = false
    }

    companion object {
        const val DEFAULT_EPSILON_KM = 0.05f
        const val DEFAULT_MAX_SEED_GAP_KM = 5f
        private const val FLOAT_TOLERANCE = 1e-4
    }
}
