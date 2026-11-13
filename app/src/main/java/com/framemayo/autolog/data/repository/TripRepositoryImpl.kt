package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.EnergyLevels
import com.framemayo.autolog.data.local.SyncState
import com.framemayo.autolog.data.local.TripEndReason
import com.framemayo.autolog.data.local.TripDao
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus
import com.framemayo.autolog.data.local.TripTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

class TripRepositoryImpl(
    private val dao: TripDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPending: () -> Unit = {},
) : TripRepository {
    // Room은 테이블이 바뀌면 같은 결과도 다시 보내므로, 실제로 달라졌을 때만 화면에 전달한다(#147).
    override val active: Flow<TripEntity?> = dao.observeActive().distinctUntilChanged()
    override val recent: Flow<List<TripEntity>> = dao.observeRecent(50).distinctUntilChanged()
    override val needsReview: Flow<List<TripEntity>> = dao.observeNeedsReview().distinctUntilChanged()

    /** 마지막으로 저장한 1초 스냅샷(누적거리·시각). 같은 값이면 DB를 조회하지도 않는다. */
    @Volatile private var lastBeatKm: Float? = null
    @Volatile private var lastBeatAt: Long = 0L
    override suspend fun get(id: Long): TripEntity? = dao.get(id)

    private fun nextUpdatedAt(trip: TripEntity): Long = maxOf(clock(), trip.updatedAt + 1)

    override suspend fun start(
        driverId: Long?,
        odometerKm: Float,
        startedAt: Long?,
        trigger: TripTrigger,
        energy: EnergyLevels?,
    ): TripEntity {
        dao.active()?.let { return it }
        val now = clock()
        val trip = TripEntity(
            driverId = driverId,
            startedAt = startedAt ?: now,
            startOdometerKm = odometerKm,
            lastOdometerKm = odometerKm,
            lastSavedAt = now,
            updatedAt = now,
            startedBy = trigger,
            startFuelPercent = energy?.fuelPercent,
            startBatteryPercent = energy?.batteryPercent,
        )
        val saved = trip.copy(id = dao.insert(trip))
        onPending()
        return saved
    }
    /**
     * 운행 중 마지막 누적거리·시각을 저장한다. 누적거리가 그대로면 [IDLE_SAVE_MS]마다만 시각을 갱신해
     * 주차 중 매초 DB 쓰기와 그에 따른 목록 재방출을 막는다(#147). 복구 판단은 수 분 단위라 이 간격이면 충분하다.
     */
    override suspend fun heartbeat(odometerKm: Float) {
        val now = clock()
        if (lastBeatKm == odometerKm && now - lastBeatAt < IDLE_SAVE_MS) return
        val trip = dao.active() ?: run { lastBeatKm = null; return }
        if (trip.lastOdometerKm == odometerKm && now - trip.lastSavedAt < IDLE_SAVE_MS) {
            lastBeatKm = odometerKm
            lastBeatAt = trip.lastSavedAt
            return
        }
        dao.heartbeat(trip.id, odometerKm, now)
        lastBeatKm = odometerKm
        lastBeatAt = now
    }

    override suspend fun end(
        odometerKm: Float,
        endedAt: Long?,
        reason: TripEndReason,
        energy: EnergyLevels?,
    ): TripEntity? {
        val trip = dao.active() ?: return null
        val now = nextUpdatedAt(trip)
        val end = (endedAt ?: now).coerceAtLeast(trip.startedAt)
        if (dao.complete(trip.id, end, odometerKm, now, reason, energy?.fuelPercent, energy?.batteryPercent) == 0) return null
        onPending()
        return dao.get(trip.id)
    }

    override suspend fun setPurpose(tripId: Long, purpose: TripPurpose, memo: String?, purposeText: String?) {
        val trip = dao.get(tripId) ?: return
        val text = if (purpose == TripPurpose.BUSINESS_GENERAL) {
            (purposeText?.trim() ?: trip.purposeText ?: "일반 업무").also {
                require(it.isNotBlank() && it.length <= 200) { "업무 목적은 1~200자로 입력해 주세요." }
            }
        } else null
        dao.updatePurpose(tripId, purpose, text, memo ?: trip.memo, nextUpdatedAt(trip))
        onPending()
    }
    override suspend fun setDriver(tripId: Long, driverId: Long): Boolean {
        val trip = dao.get(tripId) ?: return false
        if (trip.driverId != null) return false
        if (dao.assignDriver(tripId, driverId, nextUpdatedAt(trip)) == 0) return false
        onPending()
        return true
    }

    override suspend fun recoverInterrupted(): TripEntity? {
        val trip = dao.active() ?: return null
        val recovered = trip.copy(
            endedAt = trip.lastSavedAt,
            endOdometerKm = trip.lastOdometerKm,
            status = TripStatus.INTERRUPTED,
            syncState = SyncState.PENDING,
            sourceRevision = trip.sourceRevision + 1,
            updatedAt = nextUpdatedAt(trip),
        )
        dao.update(recovered)
        onPending()
        return recovered
    }

    override suspend fun lastInterrupted(): TripEntity? = dao.lastInterrupted()

    override suspend fun lastEnded(): TripEntity? = dao.lastEnded()

    override suspend fun completeInterrupted(tripId: Long, endOdometerKm: Float, endedAt: Long): TripEntity? {
        val trip = dao.get(tripId)?.takeIf { it.status == TripStatus.INTERRUPTED } ?: return null
        if (endOdometerKm < trip.startOdometerKm) return null
        val completed = trip.copy(
            endedAt = endedAt.coerceAtLeast(trip.startedAt),
            endOdometerKm = endOdometerKm,
            lastOdometerKm = endOdometerKm,
            status = TripStatus.COMPLETED,
            endedBy = TripEndReason.RECOVERED,
            syncState = SyncState.PENDING,
            sourceRevision = trip.sourceRevision + 1,
            updatedAt = nextUpdatedAt(trip),
        )
        dao.update(completed)
        onPending()
        return completed
    }

    override suspend fun pendingSync(): List<TripEntity> = dao.pendingSync()

    override suspend fun recordAck(trip: TripEntity, serverVersion: Long) =
        dao.recordAck(trip.id, trip.sourceRevision, serverVersion)

    override suspend fun rebase(trip: TripEntity, serverVersion: Long, serverRevision: Long) =
        dao.rebase(trip.id, serverVersion, serverRevision)

    override suspend fun recordConfirm(trip: TripEntity, serverVersion: Long) =
        dao.recordConfirm(trip.id, serverVersion, trip.updatedAt)

    override suspend fun updateServerVersion(trip: TripEntity, serverVersion: Long) =
        dao.setServerVersion(trip.id, serverVersion)

    override suspend fun clearServerState() = dao.clearServerState()

    override suspend fun markSynced(trip: TripEntity) = dao.markSynced(trip.id, trip.updatedAt, clock())

    override suspend fun markSyncFailed(trip: TripEntity) = dao.setSyncState(trip.id, trip.updatedAt, SyncState.FAILED)

    override suspend fun purgeSynced(retentionDays: Int): Int =
        dao.deleteSyncedBefore(clock() - retentionDays * DAY_MS)

    private companion object {
        /** 누적거리가 그대로일 때 마지막 저장 시각을 갱신하는 간격. */
        const val IDLE_SAVE_MS = 60_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
