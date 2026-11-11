package com.framemayo.autolog.data.sync

import com.framemayo.autolog.data.fakeTripDao
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.SyncState
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

private const val DRIVER_UUID = "0a94e986-dfcf-4e4a-b490-f96876c62aed"

private object FakeDrivers : DriverRepository {
    override val profiles = flowOf(emptyList<com.framemayo.autolog.data.local.DriverProfile>())
    override suspend fun addProfile(name: String, color: Int, expression: Int) = 1L
    override suspend fun editProfile(id: Long, name: String, color: Int, expression: Int) = Unit
    override suspend fun hideProfile(id: Long) = true
    override val all: Flow<List<DriverEntity>> = flowOf(emptyList())
    override suspend fun add(name: String, department: String) = 1L
    override suspend fun remoteIdOf(localId: Long): String? = if (localId == 1L) DRIVER_UUID else null
    override suspend fun setLocationConsent(driverId: Long, consent: Boolean) = Unit
    override suspend fun consentOf(driverId: Long): Boolean? = null
    override suspend fun upsertRemote(drivers: List<RemoteDriver>) = Unit
}

class SyncManagerTest {
    private var now = 1_000L
    private var pendingCalls = 0
    private var linked = true
    private val dao = fakeTripDao()
    private val repo = TripRepositoryImpl(dao, { now++ }, { pendingCalls++ })
    private val api = FakeSyncApi()
    private val sync = SyncManager(repo, FakeDrivers, api, isLinked = { linked })

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

    @Test fun purposeEditAfterSyncIsNotResentButStaysPending() = runBlocking {
        val t = finishedTrip()
        sync.syncPending()
        repo.setPurpose(t.id, TripPurpose.BUSINESS_GENERAL)
        assertTrue(sync.syncPending())
        assertEquals(1, api.uploads.size)
        assertEquals(SyncState.PENDING, dao.get(t.id)!!.syncState)
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
}
