package com.framemayo.autolog.data.sync

import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.local.SyncState
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TripSyncStatusTest {
    private fun trip(
        status: TripStatus = TripStatus.COMPLETED,
        purpose: TripPurpose = TripPurpose.BUSINESS_GENERAL,
        state: SyncState = SyncState.PENDING,
        revision: Long = 1,
        acked: Long = 1,
        confirmSentAt: Long? = null,
    ) = TripEntity(
        driverId = 1, startedAt = 0, startOdometerKm = 0f, lastOdometerKm = 5f, lastSavedAt = 0,
        status = status, purpose = purpose, syncState = state, updatedAt = 100,
        sourceRevision = revision, ackedRevision = acked, confirmSentAt = confirmSentAt,
    )

    private fun driver(remoteId: String? = "d1", status: DriverStatus? = DriverStatus.ACTIVE) =
        DriverEntity(id = 1, remoteId = remoteId, name = "a", department = "b", status = status)

    private fun status(t: TripEntity, d: DriverEntity? = driver(), linked: Boolean = true) = t.syncStatus(d, linked)

    private fun held(r: HoldReason) = TripSyncStatus.Held(r)

    @Test fun syncedAndFailedFollowStoredState() {
        assertEquals(TripSyncStatus.Synced, status(trip(state = SyncState.SYNCED)))
        assertEquals(TripSyncStatus.Failed, status(trip(state = SyncState.FAILED)))
    }

    @Test fun unlinkedVehicleHoldsEverythingPending() {
        assertEquals(held(HoldReason.NOT_LINKED), status(trip(), linked = false))
    }

    @Test fun unsentMeasurementIsWaitingNotHeld() {
        assertEquals(TripSyncStatus.Waiting, status(trip(revision = 2, acked = 1), d = null))
    }

    @Test fun interruptedTripWithPurposeIsHeld() {
        assertEquals(held(HoldReason.INTERRUPTED), status(trip(status = TripStatus.INTERRUPTED)))
    }

    @Test fun missingOrUnusableDriverHoldsConfirm() {
        assertEquals(held(HoldReason.NO_DRIVER), status(trip(), d = null))
        assertEquals(held(HoldReason.DRIVER_UNREGISTERED), status(trip(), d = driver(remoteId = null, status = null)))
        assertEquals(held(HoldReason.DRIVER_PENDING), status(trip(), d = driver(status = DriverStatus.PENDING)))
        assertEquals(held(HoldReason.DRIVER_INACTIVE), status(trip(), d = driver(status = DriverStatus.INACTIVE)))
    }

    @Test fun readyToConfirmIsWaiting() {
        assertEquals(TripSyncStatus.Waiting, status(trip()))
    }

    @Test fun unconfirmedPurposeNeedsNoDriver() {
        assertEquals(TripSyncStatus.Waiting, status(trip(purpose = TripPurpose.UNCONFIRMED), d = null))
    }

    @Test fun confirmAlreadySentIsNotHeldByDriver() {
        assertEquals(TripSyncStatus.Waiting, status(trip(confirmSentAt = 100), d = null))
    }
}
