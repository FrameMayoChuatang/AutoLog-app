package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.TripDao
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus
import kotlinx.coroutines.flow.Flow

class TripRepository(
    private val dao: TripDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val active: Flow<TripEntity?> = dao.observeActive()
    val recent: Flow<List<TripEntity>> = dao.observeRecent(50)
    val needsReview: Flow<List<TripEntity>> = dao.observeNeedsReview()
    suspend fun start(driverId: Long?, odometerKm: Float): TripEntity {
        dao.active()?.let { return it }
        val now = clock()
        val trip = TripEntity(
            driverId = driverId,
            startedAt = now,
            startOdometerKm = odometerKm,
            lastOdometerKm = odometerKm,
            lastSavedAt = now,
        )
        return trip.copy(id = dao.insert(trip))
    }
    suspend fun heartbeat(odometerKm: Float) {
        val trip = dao.active() ?: return
        dao.heartbeat(trip.id, odometerKm, clock())
    }

    suspend fun end(odometerKm: Float): TripEntity? {
        val trip = dao.active() ?: return null
        val ended = trip.copy(
            endedAt = clock(),
            endOdometerKm = odometerKm,
            lastOdometerKm = odometerKm,
            status = TripStatus.COMPLETED,
        )
        dao.update(ended)
        return ended
    }

    suspend fun setPurpose(tripId: Long, purpose: TripPurpose, memo: String? = null) {
        val trip = dao.get(tripId) ?: return
        dao.update(trip.copy(purpose = purpose, memo = memo ?: trip.memo, synced = false))
    }
    suspend fun recoverInterrupted(): TripEntity? {
        val trip = dao.active() ?: return null
        val recovered = trip.copy(
            endedAt = trip.lastSavedAt,
            endOdometerKm = trip.lastOdometerKm,
            status = TripStatus.INTERRUPTED,
        )
        dao.update(recovered)
        return recovered
    }
}
