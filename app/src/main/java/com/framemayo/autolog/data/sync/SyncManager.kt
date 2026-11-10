package com.framemayo.autolog.data.sync

import com.framemayo.autolog.data.repository.DriverRepository
import com.framemayo.autolog.data.repository.TripRepository

class SyncManager(
    private val trips: TripRepository,
    private val drivers: DriverRepository,
    private val api: SyncApi,
) {
    suspend fun syncPending(): Boolean {
        for (trip in trips.pendingSync()) {
            val remoteId = trip.driverId?.let { drivers.remoteIdOf(it) }
            when (api.upload(trip.toPayload(remoteId))) {
                SyncResult.Ok -> trips.markSynced(trip)
                SyncResult.Rejected -> trips.markSyncFailed(trip)
                SyncResult.Retry -> return false
            }
        }
        return true
    }
}
