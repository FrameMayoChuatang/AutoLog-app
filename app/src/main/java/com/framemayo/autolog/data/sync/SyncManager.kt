package com.framemayo.autolog.data.sync

import android.util.Log
import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus
import com.framemayo.autolog.data.repository.DriverRepository
import com.framemayo.autolog.data.repository.TripRepository
import kotlinx.coroutines.CancellationException

class SyncManager(
    private val trips: TripRepository,
    private val drivers: DriverRepository,
    private val api: SyncApi,
    private val isLinked: () -> Boolean = { true },
) {
    private enum class Outcome { DONE, RETRY, STOP }

    suspend fun syncPending(): Boolean {
        if (!isLinked()) return true
        var retryStreak = 0
        var allDone = true
        for (trip in trips.pendingSync()) {
            when (safely { syncOne(trip) }) {
                Outcome.DONE -> retryStreak = 0
                Outcome.RETRY -> {
                    allDone = false
                    if (++retryStreak >= MAX_RETRY_STREAK) return false
                }
                Outcome.STOP -> return true // 401: 다시 페어링해야 함
            }
        }
        return allDone
    }

    private suspend fun safely(block: suspend () -> Outcome): Outcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "trip sync failed", e)
        Outcome.RETRY
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
        return Outcome.RETRY
    }

    private suspend fun settle(measured: TripEntity): Outcome {
        val trip = trips.get(measured.id) ?: return Outcome.DONE
        if (trip.purpose == TripPurpose.UNCONFIRMED || trip.confirmSentAt == trip.updatedAt) {
            trips.markSynced(trip)
            return Outcome.DONE
        }
        return confirm(trip)
    }

    private suspend fun confirm(first: TripEntity): Outcome {
        if (first.status != TripStatus.COMPLETED) return Outcome.DONE
        val driver = first.driverId?.let { drivers.find(it) } ?: return Outcome.DONE
        val remoteId = driver.remoteId ?: return Outcome.DONE
        if (driver.status == DriverStatus.PENDING) return Outcome.RETRY // 관리자 승인을 기다리는 중이라 곧 다시 확인
        if (driver.status != DriverStatus.ACTIVE) return Outcome.DONE

        var trip = first
        repeat(MAX_ATTEMPTS) {
            val payload = trip.toConfirmPayload(remoteId, firstTime = trip.confirmSentAt == null) ?: return Outcome.DONE
            when (val r = api.confirm(payload)) {
                is SyncResult.Ok -> {
                    trips.recordConfirm(trip, r.serverVersion)
                    trips.markSynced(trip)
                    return Outcome.DONE
                }
                SyncResult.Retry -> return Outcome.RETRY
                SyncResult.Unauthorized -> return Outcome.STOP
                SyncResult.Rejected -> return fail(trip)
                SyncResult.Conflict -> when (val s = api.fetch(trip.uuid)) {
                    FetchResult.Retry -> return Outcome.RETRY
                    FetchResult.Unauthorized -> return Outcome.STOP
                    FetchResult.NotFound -> return fail(trip)
                    is FetchResult.Found -> {
                        if (s.approved) return fail(trip)
                        trips.updateServerVersion(trip, s.serverVersion)
                    }
                }
            }
            trip = trips.get(trip.id) ?: return Outcome.DONE
        }
        return Outcome.RETRY
    }

    private suspend fun fail(trip: TripEntity): Outcome {
        trips.markSyncFailed(trip)
        return Outcome.DONE
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val MAX_RETRY_STREAK = 3
        const val TAG = "SyncManager"
    }
}
