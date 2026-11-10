package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.SyncState
import com.framemayo.autolog.data.local.TripDao
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus
import kotlinx.coroutines.flow.Flow

class TripRepositoryImpl(
    private val dao: TripDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPending: () -> Unit = {},
) : TripRepository {
    override val active: Flow<TripEntity?> = dao.observeActive()
    override val recent: Flow<List<TripEntity>> = dao.observeRecent(50)
    override val needsReview: Flow<List<TripEntity>> = dao.observeNeedsReview()
    override suspend fun start(driverId: Long?, odometerKm: Float): TripEntity {
        dao.active()?.let { return it }
        val now = clock()
        val trip = TripEntity(
            driverId = driverId,
            startedAt = now,
            startOdometerKm = odometerKm,
            lastOdometerKm = odometerKm,
            lastSavedAt = now,
            updatedAt = now,
        )
        return trip.copy(id = dao.insert(trip))
    }
    override suspend fun heartbeat(odometerKm: Float) {
        val trip = dao.active() ?: return
        dao.heartbeat(trip.id, odometerKm, clock())
    }

    override suspend fun end(odometerKm: Float): TripEntity? {
        val trip = dao.active() ?: return null
        val ended = trip.copy(
            endedAt = clock(),
            endOdometerKm = odometerKm,
            lastOdometerKm = odometerKm,
            status = TripStatus.COMPLETED,
            syncState = SyncState.PENDING,
            updatedAt = clock(),
        )
        dao.update(ended)
        onPending()
        return ended
    }

    override suspend fun setPurpose(tripId: Long, purpose: TripPurpose, memo: String?) {
        val trip = dao.get(tripId) ?: return
        dao.update(trip.copy(purpose = purpose, memo = memo ?: trip.memo, syncState = SyncState.PENDING, updatedAt = clock()))
        onPending()
    }
    override suspend fun recoverInterrupted(): TripEntity? {
        val trip = dao.active() ?: return null
        val recovered = trip.copy(
            endedAt = trip.lastSavedAt,
            endOdometerKm = trip.lastOdometerKm,
            status = TripStatus.INTERRUPTED,
            syncState = SyncState.PENDING,
            updatedAt = clock(),
        )
        dao.update(recovered)
        onPending()
        return recovered
    }

    override suspend fun pendingSync(): List<TripEntity> = dao.pendingSync()

    override suspend fun markSynced(trip: TripEntity) = dao.setSyncState(trip.id, trip.updatedAt, SyncState.SYNCED)

    override suspend fun markSyncFailed(trip: TripEntity) = dao.setSyncState(trip.id, trip.updatedAt, SyncState.FAILED)

    override suspend fun purgeSynced(retentionDays: Int): Int =
        dao.deleteSyncedBefore(clock() - retentionDays * DAY_MS)

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
