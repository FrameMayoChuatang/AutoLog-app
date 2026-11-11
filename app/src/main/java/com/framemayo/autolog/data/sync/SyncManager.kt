package com.framemayo.autolog.data.sync

import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.repository.DriverRepository
import com.framemayo.autolog.data.repository.TripRepository

class SyncManager(
    private val trips: TripRepository,
    private val drivers: DriverRepository,
    private val api: SyncApi,
    private val isLinked: () -> Boolean = { true },
) {
    private enum class Outcome { DONE, RETRY, STOP }

    suspend fun syncPending(): Boolean {
        if (!isLinked()) return true
        for (trip in trips.pendingSync()) {
            when (syncOne(trip)) {
                Outcome.DONE -> Unit
                Outcome.RETRY -> return false
                Outcome.STOP -> return true // 401: 다시 페어링해야 함
            }
        }
        return true
    }

    private suspend fun syncOne(first: TripEntity): Outcome {
        var trip = first
        repeat(MAX_ATTEMPTS) {
            if (trip.sourceRevision <= trip.ackedRevision) return settle(trip)
            val driverRemoteId = trip.driverId?.let { drivers.remoteIdOf(it) }
            when (val r = api.upload(trip.toPayload(driverRemoteId))) {
                is SyncResult.Ok -> {
                    trips.recordAck(trip, r.serverVersion)
                    return settle(trip)
                }
                SyncResult.Retry -> return Outcome.RETRY
                SyncResult.Unauthorized -> return Outcome.STOP
                SyncResult.Rejected -> return fail(trip)
                SyncResult.Conflict -> when (val s = api.fetch(trip.uuid)) {
                    FetchResult.Retry -> return Outcome.RETRY
                    FetchResult.Unauthorized -> return Outcome.STOP
                    FetchResult.NotFound -> trips.rebase(trip, serverVersion = 0, serverRevision = 0)
                    is FetchResult.Found -> {
                        if (s.locked) return fail(trip)
                        trips.rebase(trip, s.serverVersion, s.sourceRevision)
                    }
                }
            }
            trip = trips.get(trip.id) ?: return Outcome.DONE
        }
        return fail(trip)
    }

    private suspend fun settle(trip: TripEntity): Outcome {
        if (trip.purpose == TripPurpose.UNCONFIRMED) trips.markSynced(trip)
        return Outcome.DONE
    }

    private suspend fun fail(trip: TripEntity): Outcome {
        trips.markSyncFailed(trip)
        return Outcome.DONE
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}
