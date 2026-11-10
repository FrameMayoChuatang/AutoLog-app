package com.framemayo.autolog.data

import com.framemayo.autolog.data.local.SyncState
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.repository.TripRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TripRepositorySyncTest {
    private var now = 1_000L
    private val repo = TripRepositoryImpl(fakeTripDao(), clock = { now++ })

    @Test fun activeTripIsNotPending() = runBlocking {
        repo.start(1, 100f)
        assertEquals(0, repo.pendingSync().size)
    }

    @Test fun endedTripIsPendingWithUuid() = runBlocking {
        val started = repo.start(1, 100f)
        repo.end(110f)
        val p = repo.pendingSync().single()
        assertEquals(started.uuid, p.uuid)
        assertEquals(SyncState.PENDING, p.syncState)
    }

    @Test fun markSyncedRemovesFromPending() = runBlocking {
        repo.start(1, 100f); repo.end(110f)
        repo.markSynced(repo.pendingSync().single())
        assertEquals(0, repo.pendingSync().size)
    }

    @Test fun editDuringSyncKeepsPending() = runBlocking {
        val t = repo.start(1, 100f).let { repo.end(110f)!! }
        val sent = repo.pendingSync().single()
        repo.setPurpose(t.id, TripPurpose.PERSONAL)   // 전송 중 수정
        repo.markSynced(sent)                          // 오래된 updatedAt -> 무시
        assertEquals(SyncState.PENDING, repo.pendingSync().single().syncState)
    }

    @Test fun failedIsNotResentUntilEdited() = runBlocking {
        val t = repo.start(1, 100f).let { repo.end(110f)!! }
        repo.markSyncFailed(repo.pendingSync().single())
        assertEquals(0, repo.pendingSync().size)
        assertEquals(SyncState.FAILED, repo.get(t.id)!!.syncState)
        repo.setPurpose(t.id, TripPurpose.PERSONAL)
        assertEquals(1, repo.pendingSync().size)
    }

    @Test fun editInSameMillisecondStillDetected() = runBlocking {
        val frozen = TripRepositoryImpl(fakeTripDao(), clock = { 5_000L })
        val t = frozen.start(1, 100f).let { frozen.end(110f)!! }
        val sent = frozen.pendingSync().single()
        frozen.setPurpose(t.id, TripPurpose.PERSONAL)
        frozen.markSynced(sent)
        assertEquals(SyncState.PENDING, frozen.get(t.id)!!.syncState)
    }

    @Test fun endUsesSingleTimestamp() = runBlocking {
        repo.start(1, 100f)
        val ended = repo.end(110f)!!
        assertEquals(ended.endedAt, ended.updatedAt)
    }

    @Test fun uuidsAreUnique() = runBlocking {
        val a = repo.start(1, 100f); repo.end(110f)
        val b = repo.start(1, 110f)
        assertNotEquals(a.uuid, b.uuid)
    }
}
