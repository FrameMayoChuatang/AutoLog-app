package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.EnergyLevels
import com.framemayo.autolog.data.local.TripEndReason
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripTrigger
import kotlinx.coroutines.flow.Flow

interface TripRepository {
    val active: Flow<TripEntity?>
    val recent: Flow<List<TripEntity>>
    val needsReview: Flow<List<TripEntity>>

    suspend fun get(id: Long): TripEntity?

    /** 진행 중 운행이 있으면 새로 만들지 않고 그 운행을 돌려준다. [startedAt]이 null이면 현재 시각. */
    suspend fun start(
        driverId: Long?,
        odometerKm: Float,
        startedAt: Long? = null,
        trigger: TripTrigger = TripTrigger.MANUAL,
        energy: EnergyLevels? = null,
    ): TripEntity
    suspend fun heartbeat(odometerKm: Float)

    /** [endedAt]이 null이면 현재 시각. 자동 종료는 주차 시작 시점의 실측 시각·누적거리를 넘긴다. */
    suspend fun end(
        odometerKm: Float,
        endedAt: Long? = null,
        reason: TripEndReason = TripEndReason.MANUAL,
        energy: EnergyLevels? = null,
    ): TripEntity?

    /** 마지막으로 INTERRUPTED가 된 운행. 재시작 후 실측 종료값을 확보하면 [completeInterrupted]로 완료한다. */
    suspend fun lastInterrupted(): TripEntity?

    /** 가장 최근에 끝난 운행(완료·중단). 자동 시작의 기준 누적거리로 쓴다. */
    suspend fun lastEnded(): TripEntity?

    /**
     * INTERRUPTED 운행을 실측 종료값으로 COMPLETED(RECOVERED) 전환한다.
     * 서버 계약상 추정값을 넣지 않으므로 호출자는 실제로 측정한 값만 넘긴다. INTERRUPTED가 아니면 null.
     */
    suspend fun completeInterrupted(tripId: Long, endOdometerKm: Float, endedAt: Long): TripEntity?
    suspend fun setPurpose(tripId: Long, purpose: TripPurpose, memo: String? = null, purposeText: String? = null)

    suspend fun setDriver(tripId: Long, driverId: Long): Boolean
    suspend fun recoverInterrupted(): TripEntity?

    suspend fun pendingSync(): List<TripEntity>

    suspend fun recordAck(trip: TripEntity, serverVersion: Long)

    suspend fun rebase(trip: TripEntity, serverVersion: Long, serverRevision: Long)

    suspend fun recordConfirm(trip: TripEntity, serverVersion: Long)

    suspend fun updateServerVersion(trip: TripEntity, serverVersion: Long)

    suspend fun clearServerState()

    suspend fun markSynced(trip: TripEntity)
    suspend fun markSyncFailed(trip: TripEntity)

    suspend fun purgeSynced(retentionDays: Int): Int
}
