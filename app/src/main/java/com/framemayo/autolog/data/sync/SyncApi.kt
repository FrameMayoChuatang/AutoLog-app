package com.framemayo.autolog.data.sync

import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripStatus

data class TripPayload(
    val uuid: String,
    val sourceRevision: Long,
    val baseServerVersion: Long,
    val driverId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val lastObservedAt: Long?,
    val startOdometerKm: Float?,
    val endOdometerKm: Float?,
    val lastOdometerKm: Float?,
    val state: String,
    val collectionIssues: List<String>?,
)

sealed interface SyncResult {
    data class Ok(val serverVersion: Long) : SyncResult

    data object Conflict : SyncResult

    data object Rejected : SyncResult

    data object Unauthorized : SyncResult

    data object Retry : SyncResult
}

sealed interface FetchResult {
    data class Found(val serverVersion: Long, val sourceRevision: Long, val locked: Boolean) : FetchResult

    data object NotFound : FetchResult

    data object Unauthorized : FetchResult

    data object Retry : FetchResult
}

interface SyncApi {
    suspend fun upload(trip: TripPayload): SyncResult

    suspend fun fetch(uuid: String): FetchResult
}

fun TripEntity.toPayload(driverRemoteId: String?): TripPayload {
    val base = TripPayload(
        uuid = uuid,
        sourceRevision = sourceRevision,
        baseServerVersion = serverVersion,
        driverId = driverRemoteId,
        startedAt = startedAt,
        endedAt = null,
        lastObservedAt = startedAt,
        startOdometerKm = startOdometerKm,
        endOdometerKm = null,
        lastOdometerKm = startOdometerKm,
        state = "RUNNING",
        collectionIssues = null,
    )
    return when (status) {
        TripStatus.ACTIVE -> base
        TripStatus.COMPLETED -> base.copy(
            endedAt = endedAt,
            lastObservedAt = endedAt,
            endOdometerKm = endOdometerKm,
            lastOdometerKm = endOdometerKm,
            state = "COMPLETED",
        )
        TripStatus.INTERRUPTED -> base.copy(
            lastObservedAt = lastSavedAt,
            lastOdometerKm = lastOdometerKm,
            state = "INTERRUPTED",
            collectionIssues = listOf("SAMPLING_INTERRUPTED"),
        )
    }
}
