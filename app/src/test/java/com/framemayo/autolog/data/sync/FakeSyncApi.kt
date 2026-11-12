package com.framemayo.autolog.data.sync

class FakeSyncApi : SyncApi {
    class Stored(
        var payload: TripPayload,
        var serverVersion: Long,
        var locked: Boolean = false,
        var approved: Boolean = false,
        var confirm: ConfirmPayload? = null,
    )

    @Volatile var online = true
    @Volatile var unauthorized = false
    @Volatile var reject = false
    @Volatile var rejectConfirm = false

    @Volatile var loseNextResponse = false
    @Volatile var alwaysConflict = false
    val retryUuids = mutableSetOf<String>()
    val conflictUuids = mutableSetOf<String>()
    val throwUuids = mutableSetOf<String>()

    val store = LinkedHashMap<String, Stored>()

    val uploads = mutableListOf<TripPayload>()

    val confirms = mutableListOf<ConfirmPayload>()

    var duringUpload: (suspend () -> Unit)? = null

    override suspend fun upload(trip: TripPayload): SyncResult {
        if (!online) return SyncResult.Retry
        if (unauthorized) return SyncResult.Unauthorized
        uploads += trip
        duringUpload?.let { duringUpload = null; it() }
        if (trip.uuid in throwUuids) error("boom")
        if (trip.uuid in retryUuids) return SyncResult.Retry
        if (alwaysConflict || trip.uuid in conflictUuids) return SyncResult.Conflict
        if (reject) return SyncResult.Rejected
        val cur = store[trip.uuid]
        val result: SyncResult = when {
            cur == null ->
                if (trip.baseServerVersion != 0L) SyncResult.Conflict
                else SyncResult.Ok(1).also { store[trip.uuid] = Stored(trip, 1) }
            trip == cur.payload -> SyncResult.Ok(cur.serverVersion)
            cur.payload.driverId != null && trip.driverId != cur.payload.driverId -> SyncResult.Conflict
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

    override suspend fun confirm(confirm: ConfirmPayload): SyncResult {
        if (!online) return SyncResult.Retry
        if (unauthorized) return SyncResult.Unauthorized
        confirms += confirm
        if (rejectConfirm) return SyncResult.Rejected
        val cur = store[confirm.uuid] ?: return SyncResult.Rejected
        if (cur.approved || cur.payload.state != "COMPLETED" || confirm.expectedVersion != cur.serverVersion) return SyncResult.Conflict
        cur.serverVersion++
        cur.locked = true
        cur.confirm = confirm
        return SyncResult.Ok(cur.serverVersion)
    }

    override suspend fun fetch(uuid: String): FetchResult {
        if (!online) return FetchResult.Retry
        if (unauthorized) return FetchResult.Unauthorized
        val cur = store[uuid] ?: return FetchResult.NotFound
        return FetchResult.Found(cur.serverVersion, cur.payload.sourceRevision, cur.locked, cur.approved)
    }

    fun serverSideEdit(uuid: String) {
        store.getValue(uuid).serverVersion++
    }
}
