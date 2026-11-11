package com.framemayo.autolog.data.sync

class FakeSyncApi : SyncApi {
    class Stored(var payload: TripPayload, var serverVersion: Long, var locked: Boolean = false)

    @Volatile var online = true
    @Volatile var unauthorized = false
    @Volatile var reject = false

    @Volatile var loseNextResponse = false

    val store = LinkedHashMap<String, Stored>()

    val uploads = mutableListOf<TripPayload>()

    override suspend fun upload(trip: TripPayload): SyncResult {
        if (!online) return SyncResult.Retry
        if (unauthorized) return SyncResult.Unauthorized
        uploads += trip
        if (reject) return SyncResult.Rejected
        val cur = store[trip.uuid]
        val result: SyncResult = when {
            cur == null ->
                if (trip.baseServerVersion != 0L) SyncResult.Conflict
                else SyncResult.Ok(1).also { store[trip.uuid] = Stored(trip, 1) }
            trip == cur.payload -> SyncResult.Ok(cur.serverVersion)
            cur.locked || trip.sourceRevision <= cur.payload.sourceRevision || trip.baseServerVersion != cur.serverVersion ->
                SyncResult.Conflict
            else -> {
                cur.payload = trip
                cur.serverVersion++
                SyncResult.Ok(cur.serverVersion)
            }
        }
        if (loseNextResponse && result is SyncResult.Ok) {
            loseNextResponse = false
            return SyncResult.Retry
        }
        return result
    }

    override suspend fun fetch(uuid: String): FetchResult {
        if (!online) return FetchResult.Retry
        if (unauthorized) return FetchResult.Unauthorized
        val cur = store[uuid] ?: return FetchResult.NotFound
        return FetchResult.Found(cur.serverVersion, cur.payload.sourceRevision, cur.locked)
    }

    fun serverSideEdit(uuid: String) {
        store.getValue(uuid).serverVersion++
    }
}
