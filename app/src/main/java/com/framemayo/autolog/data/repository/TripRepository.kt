package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import kotlinx.coroutines.flow.Flow

interface TripRepository {
    val active: Flow<TripEntity?>
    val recent: Flow<List<TripEntity>>
    val needsReview: Flow<List<TripEntity>>

    suspend fun start(driverId: Long?, odometerKm: Float): TripEntity
    suspend fun heartbeat(odometerKm: Float)
    suspend fun end(odometerKm: Float): TripEntity?
    suspend fun setPurpose(tripId: Long, purpose: TripPurpose, memo: String? = null)
    suspend fun recoverInterrupted(): TripEntity?

    suspend fun pendingSync(): List<TripEntity>

    suspend fun markSynced(trip: TripEntity)
    suspend fun markSyncFailed(trip: TripEntity)
}
