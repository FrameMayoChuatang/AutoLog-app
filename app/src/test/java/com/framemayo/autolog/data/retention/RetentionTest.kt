package com.framemayo.autolog.data.retention

import com.framemayo.autolog.data.fakeTripDao
import com.framemayo.autolog.data.local.SyncState
import com.framemayo.autolog.data.repository.TripRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionTest {
    private val day = 24L * 60 * 60 * 1000
    private var now = 0L
    private val dao = fakeTripDao()
    private val repo = TripRepositoryImpl(dao, clock = { now })

    private suspend fun finishedAt(t: Long, km: Float): com.framemayo.autolog.data.local.TripEntity {
        now = t; repo.start(1, km); return repo.end(km + 1)!!
    }

    @Test fun deletesOnlyOldSyncedTrips() = runBlocking {
        val old = finishedAt(0, 100f); repo.markSynced(old)
        val recent = finishedAt(25 * day, 200f); repo.markSynced(recent)
        now = 31 * day
        assertEquals(1, repo.purgeSynced(30))
        assertEquals(listOf(recent.uuid), listOf(dao.get(recent.id)!!.uuid))
        assertEquals(null, dao.get(old.id))
    }

    @Test fun neverDeletesUnsyncedOrActive() = runBlocking {
        finishedAt(0, 100f)
        val failed = finishedAt(1, 200f); repo.markSyncFailed(failed)
        now = 2; repo.start(1, 300f)
        now = 365 * day
        assertEquals(0, repo.purgeSynced(30))
        assertEquals(SyncState.FAILED, dao.get(failed.id)!!.syncState)
        assertEquals(3, listOf(1L, 2L, 3L).count { dao.get(it) != null })
    }

    @Test fun editAfterSyncRestartsRetentionClock() = runBlocking {
        val t = finishedAt(0, 100f); repo.markSynced(t)
        now = 29 * day
        repo.setPurpose(t.id, com.framemayo.autolog.data.local.TripPurpose.PERSONAL)
        repo.markSynced(repo.pendingSync().single())
        now = 31 * day
        assertEquals(0, repo.purgeSynced(30))
    }
}
