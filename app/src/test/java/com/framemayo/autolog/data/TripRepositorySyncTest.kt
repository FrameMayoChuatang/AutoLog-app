package com.framemayo.autolog.data

import com.framemayo.autolog.data.local.SyncState
import com.framemayo.autolog.data.local.TripDao
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus
import com.framemayo.autolog.data.repository.TripRepositoryImpl
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private fun fakeDao(): TripDao {
    val rows = mutableListOf<TripEntity>()
    var nextId = 1L
    return Proxy.newProxyInstance(TripDao::class.java.classLoader, arrayOf(TripDao::class.java)) { _, m, a ->
        when (m.name) {
            "insert" -> { val t = (a[0] as TripEntity).copy(id = nextId++); rows += t; t.id }
            "update" -> { val t = a[0] as TripEntity; rows[rows.indexOfFirst { it.id == t.id }] = t; Unit }
            "get" -> rows.firstOrNull { it.id == a[0] }
            "active" -> rows.lastOrNull { it.status == TripStatus.ACTIVE }
            "pendingSync" -> rows.filter { it.syncState != SyncState.SYNCED && it.status != TripStatus.ACTIVE }
            "setSyncState" -> {
                val i = rows.indexOfFirst { it.id == a[0] && it.updatedAt == a[1] }
                if (i >= 0) rows[i] = rows[i].copy(syncState = a[2] as SyncState)
                Unit
            }
            "observeActive", "observeRecent", "observeNeedsReview" -> flowOf<Any?>(null)
            else -> error("unexpected ${m.name}")
        }
    } as TripDao
}

class TripRepositorySyncTest {
    private var now = 1_000L
    private val repo = TripRepositoryImpl(fakeDao()) { now++ }

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

    @Test fun failedStaysRetryable() = runBlocking {
        repo.start(1, 100f); repo.end(110f)
        repo.markSyncFailed(repo.pendingSync().single())
        assertEquals(SyncState.FAILED, repo.pendingSync().single().syncState)
    }

    @Test fun uuidsAreUnique() = runBlocking {
        val a = repo.start(1, 100f); repo.end(110f)
        val b = repo.start(1, 110f)
        assertNotEquals(a.uuid, b.uuid)
    }
}
