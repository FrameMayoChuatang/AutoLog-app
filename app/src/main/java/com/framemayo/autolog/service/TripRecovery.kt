package com.framemayo.autolog.service

import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripStatus
import com.framemayo.autolog.data.repository.TripRepository
import com.framemayo.autolog.vehicle.OdometerValidator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

sealed interface RecoveryResult {
    /** 남은 ACTIVE 운행이 없다. */
    data object None : RecoveryResult

    /** 짧은 공백 뒤 같은 운행을 이어 간다(ACTIVE 유지). */
    data class Resumed(val trip: TripEntity) : RecoveryResult

    /** 차가 움직이지 않아 마지막 스냅샷이 실측 종료값이다. INTERRUPTED를 거쳐 COMPLETED(RECOVERED). */
    data class Completed(val trip: TripEntity) : RecoveryResult

    /** 종료값을 실측할 수 없어 INTERRUPTED로 남긴다. 추정값은 넣지 않는다. */
    data class Interrupted(val trip: TripEntity) : RecoveryResult
}

/**
 * 강제 종료·프로세스 사망 뒤 서비스 시작 시 남은 ACTIVE 운행을 정리한다(#40).
 *
 * 서버 계약: 마지막 스냅샷만 있는 기록은 INTERRUPTED로 보내고 종료값을 추정하지 않는다.
 * 실제 종료값(재시작 후 읽은 누적거리가 마지막 스냅샷과 같음)을 확보했을 때만 COMPLETED로 올린다.
 * 서비스 실행마다 새 인스턴스를 만든다(늦은 승격은 이번 실행에서 중단시킨 운행만 대상).
 */
class TripRecovery(
    private val trips: TripRepository,
    private val activeTrip: suspend () -> TripEntity? = { trips.active.first() },
    private val resumeWindowMs: Long = RESUME_WINDOW_MS,
    private val sameOdometerKm: Float = SAME_ODOMETER_KM,
) {
    private val mutex = Mutex()

    /** 유효한 누적거리 없이 INTERRUPTED로 만든 운행. 첫 유효 누적거리로 승격 여부를 한 번만 판단한다. */
    @Volatile private var pendingTripId: Long? = null

    val awaitingOdometer: Boolean get() = pendingTripId != null

    /** [currentKm]은 재시작 직후 한 번 읽은 누적거리(못 읽었으면 null), [now]는 현재 시각. */
    suspend fun recover(currentKm: Float?, now: Long): RecoveryResult = mutex.withLock {
        val trip = activeTrip()?.takeIf { it.status == TripStatus.ACTIVE } ?: return RecoveryResult.None
        val valid = OdometerValidator.isValid(currentKm, trip.lastOdometerKm)
        if (valid && now - trip.lastSavedAt <= resumeWindowMs) return RecoveryResult.Resumed(trip)

        val interrupted = trips.recoverInterrupted() ?: return RecoveryResult.None
        if (valid && sameKm(currentKm!!, trip.lastOdometerKm)) {
            trips.completeInterrupted(interrupted.id, trip.lastOdometerKm, trip.lastSavedAt)
                ?.let { return RecoveryResult.Completed(it) }
        }
        // null·0·NaN·역행 값은 "아직 못 읽음"으로 보고 첫 유효값을 기다린다.
        if (!valid) pendingTripId = interrupted.id
        RecoveryResult.Interrupted(interrupted)
    }

    /**
     * 시작 시 누적거리를 못 읽었다면 첫 유효 누적거리가 들어올 때 호출한다.
     * 마지막 스냅샷과 같을 때만 실측 종료값으로 승격하고, 움직였으면 INTERRUPTED 그대로 둔다(추정 금지).
     * 판단은 한 번뿐이며 이후 호출은 [RecoveryResult.None].
     */
    suspend fun onFirstValidOdometer(km: Float, now: Long): RecoveryResult = mutex.withLock {
        val id = pendingTripId ?: return RecoveryResult.None
        pendingTripId = null
        val trip = trips.lastInterrupted()?.takeIf { it.id == id } ?: return RecoveryResult.None
        if (!OdometerValidator.isValid(km, trip.lastOdometerKm) || !sameKm(km, trip.lastOdometerKm)) {
            return RecoveryResult.None
        }
        trips.completeInterrupted(id, trip.lastOdometerKm, trip.lastSavedAt)
            ?.let { RecoveryResult.Completed(it) } ?: RecoveryResult.None
    }

    private fun sameKm(a: Float, b: Float) = abs(a - b) <= sameOdometerKm

    companion object {
        /** 이 시간 안에 같은 차량 상태로 돌아오면 같은 운행으로 이어 간다. */
        const val RESUME_WINDOW_MS = 10L * 60 * 1000

        /** 누적거리 해상도(0.1km) 절반. 이 이내면 "움직이지 않음"으로 본다. */
        const val SAME_ODOMETER_KM = 0.05f
    }
}
