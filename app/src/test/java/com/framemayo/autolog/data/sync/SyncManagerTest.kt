package com.framemayo.autolog.data.sync

import com.framemayo.autolog.data.fakeTripDao
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.local.SyncState
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.repository.DriverRepository
import com.framemayo.autolog.data.repository.RemoteDriver
import com.framemayo.autolog.data.repository.TripRepositoryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val DRIVER_UUID = "0a94e986-dfcf-4e4a-b490-f96876c62aed"

private class FakeDrivers : DriverRepository {
    var remoteId: String? = DRIVER_UUID
    var status: DriverStatus? = DriverStatus.ACTIVE
    override val profiles = flowOf(emptyList<com.framemayo.autolog.data.local.DriverProfile>())
    override suspend fun addProfile(name: String, color: Int, expression: Int) = 1L
    override suspend fun editProfile(id: Long, name: String, color: Int, expression: Int) = Unit
    override suspend fun hideProfile(id: Long) = true
    override val profileSettings = flowOf(emptyList<com.framemayo.autolog.data.local.DriverProfileEntity>())
    override val all: Flow<List<DriverEntity>> = flowOf(emptyList())
    override suspend fun add(name: String, department: String) = 1L
    override suspend fun remoteIdOf(localId: Long): String? = if (localId == 1L) remoteId else null
    override suspend fun find(localId: Long): DriverEntity? =
        if (localId == 1L) DriverEntity(id = 1, remoteId = remoteId, name = "Kim", department = "Sales", status = status) else null
    override suspend fun setLocationConsent(driverId: Long, consent: Boolean) = Unit
    override suspend fun consentOf(driverId: Long): Boolean? = null
    override suspend fun upsertRemote(drivers: List<RemoteDriver>) = Unit
    override suspend fun snapshot(): List<DriverEntity> = emptyList()
    override suspend fun link(localId: Long, remote: RemoteDriver) = Unit
    override suspend fun consentPending(): List<DriverEntity> = emptyList()
    override suspend fun markConsentSent(driver: DriverEntity) = Unit
    override suspend fun clearServerLinks() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncManagerTest {
    private var now = 1_000L
    private var pendingCalls = 0
    private var linked = true
    private val dao = fakeTripDao()
    private val repo = TripRepositoryImpl(dao, { now++ }, { pendingCalls++ })
    private val api = FakeSyncApi()
    private val driversFake = FakeDrivers()
    private val sync = SyncManager(repo, driversFake, api, isLinked = { linked })

    private suspend fun finishedTrip(startKm: Float = 100f) = repo.start(1, startKm).let { repo.end(startKm + 10)!! }

    @Test fun offlineKeepsPendingThenOnlineUploadsOnce() = runBlocking {
        val t = finishedTrip()
        api.online = false
        assertFalse(sync.syncPending())
        assertEquals(SyncState.PENDING, dao.get(t.id)!!.syncState)
        assertEquals(0, api.uploads.size)

        api.online = true
        assertTrue(sync.syncPending())
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
        val sent = api.uploads.single()
        assertEquals(2, sent.sourceRevision)
        assertEquals(0, sent.baseServerVersion)
        assertEquals("COMPLETED", sent.state)
        assertEquals(DRIVER_UUID, sent.driverId)
    }

    @Test fun runningThenCompletedUsesServerVersionAsBase() = runBlocking {
        val t = repo.start(1, 100f)
        assertTrue(sync.syncPending())
        val running = api.uploads.single()
        assertEquals("RUNNING", running.state)
        assertEquals(1, running.sourceRevision)
        assertNull(running.endedAt)
        assertEquals(100f, running.lastOdometerKm)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)

        repo.end(110f)
        assertTrue(sync.syncPending())
        val done = api.uploads.last()
        assertEquals(2, done.sourceRevision)
        assertEquals(1, done.baseServerVersion)
        assertEquals(2, api.store.getValue(t.uuid).serverVersion)
    }

    @Test fun lostResponseResendsSamePayloadAndStaysOneRecord() = runBlocking {
        val t = finishedTrip()
        api.loseNextResponse = true
        assertFalse(sync.syncPending())
        assertEquals(SyncState.PENDING, dao.get(t.id)!!.syncState)

        assertTrue(sync.syncPending())
        assertEquals(2, api.uploads.size)
        assertEquals(api.uploads[0], api.uploads[1])
        assertEquals(1, api.store.size)
        assertEquals(1, api.store.getValue(t.uuid).serverVersion)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
    }

    @Test fun serverSideVersionDriftIsRebasedAndResent() = runBlocking {
        val t = repo.start(1, 100f)
        sync.syncPending()
        api.serverSideEdit(t.uuid)
        repo.end(110f)

        assertTrue(sync.syncPending())
        val saved = api.store.getValue(t.uuid)
        assertEquals("COMPLETED", saved.payload.state)
        assertEquals(2, saved.payload.baseServerVersion)
        assertTrue(saved.payload.sourceRevision > 2)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
        assertEquals(3, api.uploads.size)
    }

    @Test fun lockedMeasurementBecomesFailedAndIsNotResent() = runBlocking {
        val t = repo.start(1, 100f)
        sync.syncPending()
        api.store.getValue(t.uuid).locked = true
        repo.end(110f)

        assertTrue(sync.syncPending())
        assertEquals(SyncState.FAILED, dao.get(t.id)!!.syncState)
        val calls = api.uploads.size
        assertTrue(sync.syncPending())
        assertEquals(calls, api.uploads.size)
    }

    @Test fun rejectedBecomesFailedWithoutRetryLoop() = runBlocking {
        val t = finishedTrip()
        api.reject = true
        assertTrue(sync.syncPending())
        assertEquals(SyncState.FAILED, dao.get(t.id)!!.syncState)
        assertEquals(1, api.uploads.size)
        assertTrue(sync.syncPending())
        assertEquals(1, api.uploads.size)
    }

    @Test fun unauthorizedStopsWithoutRetryOrFailure() = runBlocking {
        val t = finishedTrip()
        api.unauthorized = true
        assertTrue(sync.syncPending())
        assertEquals(SyncState.PENDING, dao.get(t.id)!!.syncState)
    }

    @Test fun notLinkedSendsNothing() = runBlocking {
        finishedTrip()
        linked = false
        assertTrue(sync.syncPending())
        assertEquals(0, api.uploads.size)
        linked = true
        assertTrue(sync.syncPending())
        assertEquals(1, api.uploads.size)
    }

    @Test fun interruptedIsSentWithoutEstimatedEndValues() = runBlocking {
        val t = repo.start(1, 100f)
        repo.heartbeat(104.5f)
        repo.recoverInterrupted()
        assertTrue(sync.syncPending())
        val sent = api.uploads.single()
        assertEquals("INTERRUPTED", sent.state)
        assertNull(sent.endedAt)
        assertNull(sent.endOdometerKm)
        assertEquals(104.5f, sent.lastOdometerKm)
        assertEquals(listOf("SAMPLING_INTERRUPTED"), sent.collectionIssues)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
    }

    @Test fun oldestTripIsSentFirstAndOneOfflineStopsTheRest() = runBlocking {
        val a = finishedTrip(100f)
        val b = finishedTrip(200f)
        assertTrue(sync.syncPending())
        assertEquals(listOf(a.uuid, b.uuid), api.uploads.map { it.uuid })
    }

    @Test fun startTriggersSyncSoRunningStateIsSent() = runBlocking {
        pendingCalls = 0
        repo.start(1, 100f)
        assertEquals(1, pendingCalls)
    }

    private suspend fun finishedWithPurpose(purpose: TripPurpose, memo: String? = null, startKm: Float = 100f): TripEntity {
        val t = finishedTrip(startKm)
        repo.setPurpose(t.id, purpose, memo)
        return t
    }

    @Test fun chosenPurposeIsConfirmedAfterMeasurementAndBecomesSynced() = runBlocking {
        val t = finishedWithPurpose(TripPurpose.BUSINESS_GENERAL, "계약 미팅")
        assertTrue(sync.syncPending())
        assertEquals(1, api.uploads.size)
        val c = api.confirms.single()
        assertEquals("BUSINESS", c.classification)
        assertEquals("일반 업무", c.purposeText)
        assertEquals(DRIVER_UUID, c.driverId)
        assertEquals("계약 미팅", c.note)
        assertEquals("차량에서 운전자 확인", c.reason)
        assertEquals(1, c.expectedVersion)
        val local = dao.get(t.id)!!
        assertEquals(SyncState.SYNCED, local.syncState)
        assertEquals(local.updatedAt, local.confirmSentAt)
        assertEquals(2, local.serverVersion)
        assertTrue(api.store.getValue(t.uuid).locked)
    }

    @Test fun classificationsMapToServerValues() = runBlocking {
        finishedWithPurpose(TripPurpose.BUSINESS_COMMUTE, startKm = 100f)
        finishedWithPurpose(TripPurpose.PERSONAL, startKm = 200f)
        assertTrue(sync.syncPending())
        val (commute, personal) = api.confirms
        assertEquals("COMMUTE", commute.classification)
        assertNull(commute.purposeText)
        assertEquals("PERSONAL", personal.classification)
        assertNull(personal.purposeText)
        assertNull(personal.note)
    }

    @Test fun unconfirmedTripIsSyncedWithoutConfirm() = runBlocking {
        val t = finishedTrip()
        assertTrue(sync.syncPending())
        assertEquals(0, api.confirms.size)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
    }

    @Test fun purposeChosenBeforeMeasurementReachedServerWaitsForIt() = runBlocking {
        finishedWithPurpose(TripPurpose.PERSONAL)
        api.online = false
        assertFalse(sync.syncPending())
        api.online = true
        assertTrue(sync.syncPending())
        assertEquals(1, api.uploads.size)
        assertEquals(1, api.confirms.size)
    }

    @Test fun changingPurposeAfterConfirmIsResentWithChangeReason() = runBlocking {
        val t = finishedWithPurpose(TripPurpose.BUSINESS_GENERAL)
        sync.syncPending()
        repo.setPurpose(t.id, TripPurpose.PERSONAL)
        assertTrue(sync.syncPending())
        assertEquals(2, api.confirms.size)
        val second = api.confirms.last()
        assertEquals("PERSONAL", second.classification)
        assertEquals("차량에서 목적 변경", second.reason)
        assertEquals(2, second.expectedVersion)
        assertEquals(3, dao.get(t.id)!!.serverVersion)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
    }

    @Test fun confirmIsHeldUntilDriverIsLinkedAndApproved() = runBlocking {
        val t = finishedWithPurpose(TripPurpose.PERSONAL)
        driversFake.status = DriverStatus.PENDING
        assertFalse(sync.syncPending()) // 승인 대기는 곧 다시 확인한다
        assertEquals(1, api.uploads.size)
        assertEquals(0, api.confirms.size)
        assertEquals(SyncState.PENDING, dao.get(t.id)!!.syncState)

        driversFake.status = null; driversFake.remoteId = null
        assertTrue(sync.syncPending())
        assertEquals(0, api.confirms.size)

        driversFake.remoteId = DRIVER_UUID; driversFake.status = DriverStatus.ACTIVE
        assertTrue(sync.syncPending())
        assertEquals(1, api.confirms.size)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
    }

    @Test fun tripAwaitingDriverApprovalIsSentOnNextRunAfterApproval() = runBlocking {
        val t = finishedWithPurpose(TripPurpose.PERSONAL)
        driversFake.status = DriverStatus.PENDING
        assertFalse(sync.syncPending())
        assertEquals(0, api.confirms.size)

        driversFake.status = DriverStatus.ACTIVE
        assertTrue(sync.syncPending())
        assertEquals(1, api.confirms.size)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
    }

    @Test fun oneBlockedTripDoesNotStopLaterTrips() = runBlocking {
        val a = finishedTrip(100f)
        val b = finishedTrip(200f)
        api.retryUuids += a.uuid
        assertFalse(sync.syncPending())
        assertEquals(SyncState.PENDING, dao.get(a.id)!!.syncState)
        assertEquals(SyncState.SYNCED, dao.get(b.id)!!.syncState)
    }

    @Test fun tripThatThrowsDoesNotStopLaterTrips() = runBlocking {
        val a = finishedTrip(100f)
        val b = finishedTrip(200f)
        api.throwUuids += a.uuid
        assertFalse(sync.syncPending())
        assertEquals(SyncState.SYNCED, dao.get(b.id)!!.syncState)
    }

    @Test fun consecutiveRetriesStopTheRunSoOfflineDoesNotWaitForEveryTrip() = runBlocking {
        repeat(5) { finishedTrip(100f + it * 100) }
        api.online = false
        assertFalse(sync.syncPending())
        assertEquals(0, api.uploads.size)
    }

    @Test fun repeatedConflictsRetryLaterInsteadOfFailing() = runBlocking {
        val t = finishedTrip()
        api.alwaysConflict = true
        assertFalse(sync.syncPending())
        assertEquals(SyncState.PENDING, dao.get(t.id)!!.syncState)

        api.alwaysConflict = false
        assertTrue(sync.syncPending())
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
    }

    @Test fun clearingServerStateResetsUnsyncedTripsOnly() = runBlocking {
        val synced = finishedTrip(100f)
        sync.syncPending()
        val unsent = finishedWithPurpose(TripPurpose.PERSONAL, startKm = 200f)
        api.online = false
        sync.syncPending()
        api.online = true
        repo.markSyncFailed(dao.get(unsent.id)!!)

        repo.clearServerState()

        assertEquals(SyncState.SYNCED, dao.get(synced.id)!!.syncState)
        val u = dao.get(unsent.id)!!
        assertEquals(SyncState.PENDING, u.syncState)
        assertEquals(0, u.ackedRevision)
        assertEquals(0, u.serverVersion)
        assertNull(u.confirmSentAt)
    }

    @Test fun tripsWithoutDriverAndInterruptedTripsAreHeld() = runBlocking {
        val noDriver = repo.start(null, 100f).let { repo.end(110f)!! }
        repo.setPurpose(noDriver.id, TripPurpose.PERSONAL)
        val interrupted = repo.start(1, 200f)
        repo.recoverInterrupted()
        repo.setPurpose(interrupted.id, TripPurpose.PERSONAL)
        assertTrue(sync.syncPending())
        assertEquals(0, api.confirms.size)
        assertEquals(SyncState.PENDING, dao.get(noDriver.id)!!.syncState)
        assertEquals(SyncState.PENDING, dao.get(interrupted.id)!!.syncState)
    }

    @Test fun versionDriftOnConfirmIsRebasedAndRetried() = runBlocking {
        val t = finishedTrip()
        sync.syncPending()
        api.serverSideEdit(t.uuid)
        repo.setPurpose(t.id, TripPurpose.PERSONAL)

        assertTrue(sync.syncPending())
        assertEquals(listOf(1L, 2L), api.confirms.map { it.expectedVersion })
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
        assertEquals(3, dao.get(t.id)!!.serverVersion)
    }

    @Test fun approvedRecordFailsAndRetriesOnlyAfterNewEdit() = runBlocking {
        val t = finishedTrip()
        sync.syncPending()
        api.store.getValue(t.uuid).approved = true
        repo.setPurpose(t.id, TripPurpose.PERSONAL)

        assertTrue(sync.syncPending())
        assertEquals(SyncState.FAILED, dao.get(t.id)!!.syncState)
        val calls = api.confirms.size
        assertTrue(sync.syncPending())
        assertEquals(calls, api.confirms.size)

        repo.setPurpose(t.id, TripPurpose.BUSINESS_COMMUTE)
        assertEquals(SyncState.PENDING, dao.get(t.id)!!.syncState)
        assertTrue(sync.syncPending())
        assertTrue(api.confirms.size > calls)
    }

    @Test fun rejectedConfirmBecomesFailed() = runBlocking {
        val t = finishedWithPurpose(TripPurpose.PERSONAL)
        api.rejectConfirm = true
        assertTrue(sync.syncPending())
        assertEquals(SyncState.FAILED, dao.get(t.id)!!.syncState)
        assertEquals(1, api.confirms.size)
    }

    @Test fun confirmRetriesWhenOfflineAndStopsWhenUnauthorized() = runBlocking {
        val t = finishedWithPurpose(TripPurpose.PERSONAL)
        api.loseNextResponse = true
        assertFalse(sync.syncPending())
        assertTrue(sync.syncPending())
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)

        val u = finishedWithPurpose(TripPurpose.PERSONAL, startKm = 300f)
        api.unauthorized = true
        assertTrue(sync.syncPending())
        assertEquals(SyncState.PENDING, dao.get(u.id)!!.syncState)
    }

    @Test fun driverAssignedAfterMeasurementIsSentWithNewRevision() = runBlocking {
        val t = repo.start(null, 100f).let { repo.end(110f)!! }
        assertTrue(sync.syncPending())
        assertNull(api.uploads.single().driverId)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)

        assertTrue(repo.setDriver(t.id, 1))
        assertEquals(SyncState.PENDING, dao.get(t.id)!!.syncState)
        assertTrue(sync.syncPending())
        val second = api.uploads.last()
        assertEquals(DRIVER_UUID, second.driverId)
        assertEquals(3, second.sourceRevision)
        assertEquals(1, second.baseServerVersion)
        assertEquals(DRIVER_UUID, api.store.getValue(t.uuid).payload.driverId)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
    }

    @Test fun heldConfirmIsSentAfterDriverIsAssigned() = runBlocking {
        val t = repo.start(null, 100f).let { repo.end(110f)!! }
        repo.setPurpose(t.id, TripPurpose.PERSONAL)
        assertTrue(sync.syncPending())
        assertEquals(0, api.confirms.size)
        assertEquals(SyncState.PENDING, dao.get(t.id)!!.syncState)

        repo.setDriver(t.id, 1)
        assertTrue(sync.syncPending())
        assertEquals(1, api.confirms.size)
        assertEquals(DRIVER_UUID, api.confirms.single().driverId)
        assertEquals(SyncState.SYNCED, dao.get(t.id)!!.syncState)
    }

    @Test fun driverAssignedToInterruptedTripIsSentButStillNotConfirmable() = runBlocking {
        val t = repo.start(null, 100f)
        repo.recoverInterrupted()
        sync.syncPending()
        repo.setDriver(t.id, 1)
        repo.setPurpose(t.id, TripPurpose.PERSONAL)
        assertTrue(sync.syncPending())
        val last = api.uploads.last()
        assertEquals("INTERRUPTED", last.state)
        assertEquals(DRIVER_UUID, last.driverId)
        assertEquals(0, api.confirms.size)
        assertEquals(SyncState.PENDING, dao.get(t.id)!!.syncState)
    }
}
