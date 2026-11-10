package com.framemayo.autolog.data

import com.framemayo.autolog.data.local.SyncState
import com.framemayo.autolog.data.local.TripDao
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripStatus
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf

/** 메모리 TripDao. Room은 JVM 단위테스트에서 못 쓰므로 필요한 메서드만 구현. */
internal fun fakeTripDao(): TripDao {
    val rows = mutableListOf<TripEntity>()
    var nextId = 1L
    return Proxy.newProxyInstance(TripDao::class.java.classLoader, arrayOf(TripDao::class.java)) { _, m, a ->
        when (m.name) {
            "insert" -> { val t = (a[0] as TripEntity).copy(id = nextId++); rows += t; t.id }
            "update" -> { val t = a[0] as TripEntity; rows[rows.indexOfFirst { it.id == t.id }] = t; Unit }
            "get" -> rows.firstOrNull { it.id == a[0] }
            "active" -> rows.lastOrNull { it.status == TripStatus.ACTIVE }
            "pendingSync" -> rows.filter { it.syncState == SyncState.PENDING && it.status != TripStatus.ACTIVE }
            "deleteSyncedBefore" -> {
                val n = rows.size
                rows.removeAll { it.syncState == SyncState.SYNCED && it.status != TripStatus.ACTIVE && (it.syncedAt ?: Long.MAX_VALUE) < (a[0] as Long) }
                n - rows.size
            }
            "markSynced" -> {
                val i = rows.indexOfFirst { it.id == a[0] && it.updatedAt == a[1] }
                if (i >= 0) rows[i] = rows[i].copy(syncState = SyncState.SYNCED, syncedAt = a[2] as Long)
                Unit
            }
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

