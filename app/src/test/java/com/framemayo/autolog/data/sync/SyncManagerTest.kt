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
import org.junit.Assert.assertTrue
import org.junit.Test

private object FakeDrivers : DriverRepository {
    override val all: Flow<List<DriverEntity>> = flowOf(emptyList())
    override suspend fun add(name: String, department: String) = 1L
    override suspend fun remoteIdOf(localId: Long): Long? = if (localId == 1L) 100L else null
    override suspend fun setLocationConsent(driverId: Long, consent: Boolean) = Unit
    override suspend fun upsertRemote(drivers: List<RemoteDriver>) = Unit
}

class SyncManagerTest {
    private var now = 1_000L
    private var pendingCalls = 0
    private val repo = TripRepositoryImpl(fakeTripDao(), { now++ }, { pendingCalls++ })
    private val api = FakeSyncApi()
    private val sync = SyncManager(repo, FakeDrivers, api)

    private suspend fun finishedTrip(startKm: Float = 100f) {
        repo.start(1, startKm); repo.end(startKm + 10)
    }

    @Test fun offlineKeepsPendingThenOnlineUploadsOnce() = runBlocking {
        finishedTrip()
        api.online = false
        assertFalse(sync.syncPending())
        assertEquals(1, repo.pendingSync().size)
        assertEquals(0, api.received.size)

        api.online = true
        assertTrue(sync.syncPending())
        assertEquals(0, repo.pendingSync().size)
        assertEquals(1, api.received.size)
        assertEquals(100L, api.received.single().driverRemoteId)
    }

    @Test fun alreadySyncedIsNotResent() = runBlocking {
        finishedTrip()
        sync.syncPending()
        api.received // 1건
        sync.syncPending()
        assertEquals(1, api.received.size)
    }

    @Test fun duplicateUploadOfSameUuidStaysOneRecord() = runBlocking {
        finishedTrip()
        val trip = repo.pendingSync().single()
        api.upload(trip.toPayload(100L))
        api.upload(trip.toPayload(100L)) // 응답 유실로 재전송된 상황
        assertEquals(1, api.received.size)
    }

    @Test fun rejectedBecomesFailedWithoutRetryLoop() = runBlocking {
        finishedTrip()
        api.reject = true
        assertTrue(sync.syncPending())
        assertEquals(SyncState.FAILED, repo.pendingSync().single().syncState)
    }

    @Test fun stopsAtFirstNetworkErrorKeepingOrder() = runBlocking {
        finishedTrip(100f); finishedTrip(200f)
        api.online = false
        assertFalse(sync.syncPending())
        api.online = true
        sync.syncPending()
        assertEquals(listOf(100f, 200f), api.received.map { it.startOdometerKm })
    }

    @Test fun repositoryNotifiesWhenPending() = runBlocking {
        finishedTrip()
        val t = repo.pendingSync().single()
        repo.setPurpose(t.id, TripPurpose.PERSONAL)
        assertEquals(2, pendingCalls) // end + setPurpose
    }
}
