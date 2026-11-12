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

class TripRepositoryImpl(
    private val dao: TripDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPending: () -> Unit = {},
) : TripRepository {
    override val active: Flow<TripEntity?> = dao.observeActive()
    override val recent: Flow<List<TripEntity>> = dao.observeRecent(50)
    override val needsReview: Flow<List<TripEntity>> = dao.observeNeedsReview()
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
    override suspend fun heartbeat(odometerKm: Float) {
        val trip = dao.active() ?: return
        dao.heartbeat(trip.id, odometerKm, clock())
    }

    override suspend fun end(
        odometerKm: Float,
        endedAt: Long?,
        reason: TripEndReason,
        energy: EnergyLevels?,
    ): TripEntity? {
        val trip = dao.active() ?: return null
        val now = nextUpdatedAt(trip)
        val ended = trip.copy(
            endedAt = (endedAt ?: now).coerceAtLeast(trip.startedAt),
            endOdometerKm = odometerKm,
            lastOdometerKm = odometerKm,
            status = TripStatus.COMPLETED,
            syncState = SyncState.PENDING,
            sourceRevision = trip.sourceRevision + 1,
            updatedAt = now,
            endedBy = reason,
            endFuelPercent = energy?.fuelPercent ?: trip.endFuelPercent,
            endBatteryPercent = energy?.batteryPercent ?: trip.endBatteryPercent,
        )
        dao.update(ended)
        onPending()
        return ended
    }

    override suspend fun setPurpose(tripId: Long, purpose: TripPurpose, memo: String?, purposeText: String?) {
        val trip = dao.get(tripId) ?: return
        val text = if (purpose == TripPurpose.BUSINESS_GENERAL) {
            (purposeText?.trim() ?: trip.purposeText ?: "일반 업무").also {
                require(it.isNotBlank() && it.length <= 200) { "업무 목적은 1~200자로 입력해 주세요." }
            }
        } else null
        dao.update(trip.copy(purpose = purpose, purposeText = text, memo = memo ?: trip.memo, syncState = SyncState.PENDING, updatedAt = nextUpdatedAt(trip)))
        onPending()
    }
    override suspend fun setDriver(tripId: Long, driverId: Long): Boolean {
        val trip = dao.get(tripId) ?: return false
        if (trip.driverId != null) return false
        dao.update(
            trip.copy(
                driverId = driverId,
                syncState = SyncState.PENDING,
                sourceRevision = trip.sourceRevision + 1,
                updatedAt = nextUpdatedAt(trip),
            ),
        )
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
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
