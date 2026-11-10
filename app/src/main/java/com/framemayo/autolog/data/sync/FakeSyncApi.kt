package com.framemayo.autolog.data.sync

class FakeSyncApi : SyncApi {
    @Volatile var online = true
    @Volatile var reject = false
    private val store = LinkedHashMap<String, TripPayload>()

    val received: List<TripPayload> get() = synchronized(store) { store.values.toList() }

    override suspend fun upload(trip: TripPayload): SyncResult {
        if (!online) return SyncResult.Retry
        if (reject) return SyncResult.Rejected
        synchronized(store) { store[trip.uuid] = trip }
        return SyncResult.Ok
    }
}
