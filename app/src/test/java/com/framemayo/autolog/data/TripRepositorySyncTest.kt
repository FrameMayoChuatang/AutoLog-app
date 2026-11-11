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

    @Test fun activeTripIsPendingSoRunningStateReachesServer() = runBlocking {
        val t = repo.start(1, 100f)
        assertEquals(listOf(t.uuid), repo.pendingSync().map { it.uuid })
        assertEquals(1, t.sourceRevision)
    }

    @Test fun revisionIncreasesOnlyWhenMeasurementsChange() = runBlocking {
        repo.start(1, 100f)
        repo.heartbeat(105f)
        val ended = repo.end(110f)!!
        assertEquals(2, ended.sourceRevision)
        repo.setPurpose(ended.id, TripPurpose.PERSONAL)
        assertEquals(2, repo.get(ended.id)!!.sourceRevision)
    }

    @Test fun ackAndRebaseKeepVersions() = runBlocking {
        val t = repo.start(1, 100f)
        repo.recordAck(t, serverVersion = 7)
        assertEquals(1, repo.get(t.id)!!.ackedRevision)
        assertEquals(7, repo.get(t.id)!!.serverVersion)
        repo.recordAck(t, serverVersion = 3)
        assertEquals(7, repo.get(t.id)!!.serverVersion)
        repo.rebase(t, serverVersion = 9, serverRevision = 5)
        val r = repo.get(t.id)!!
        assertEquals(9, r.serverVersion)
        assertEquals(6, r.sourceRevision)
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

    @Test fun confirmIsRecordedOnlyWhenPurposeIsUnchangedSinceSending() = runBlocking {
        val t = repo.start(1, 100f).let { repo.end(110f)!! }
        repo.setPurpose(t.id, TripPurpose.PERSONAL)
        val sentCopy = repo.get(t.id)!!
        repo.setPurpose(t.id, TripPurpose.BUSINESS_GENERAL)
        repo.recordConfirm(sentCopy, serverVersion = 5)
        val changed = repo.get(t.id)!!
        assertEquals(5, changed.serverVersion)
        assertEquals(null, changed.confirmSentAt)

        repo.recordConfirm(changed, serverVersion = 6)
        assertEquals(changed.updatedAt, repo.get(t.id)!!.confirmSentAt)
    }

    @Test fun updateServerVersionKeepsRevisions() = runBlocking {
        val t = repo.start(1, 100f).let { repo.end(110f)!! }
        repo.updateServerVersion(t, 9)
        val r = repo.get(t.id)!!
        assertEquals(9, r.serverVersion)
        assertEquals(t.sourceRevision, r.sourceRevision)
    }
}
