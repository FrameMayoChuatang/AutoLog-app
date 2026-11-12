package com.framemayo.autolog.data.sync

import com.framemayo.autolog.data.local.TripEndReason
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
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
    data class Found(val serverVersion: Long, val sourceRevision: Long, val locked: Boolean, val approved: Boolean) : FetchResult

    data object NotFound : FetchResult

    data object Unauthorized : FetchResult

    data object Retry : FetchResult
}

data class ConfirmPayload(
    val uuid: String,
    val expectedVersion: Long,
    val driverId: String,
    val classification: String,
    val purposeText: String?,
    val note: String?,
    val reason: String,
)

interface SyncApi {
    suspend fun upload(trip: TripPayload): SyncResult

    suspend fun confirm(confirm: ConfirmPayload): SyncResult

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
            collectionIssues = if (endedBy == TripEndReason.RECOVERED) emptyList() else null,
        )
        TripStatus.INTERRUPTED -> base.copy(
            lastObservedAt = lastSavedAt,
            lastOdometerKm = lastOdometerKm,
            state = "INTERRUPTED",
            collectionIssues = listOf("SAMPLING_INTERRUPTED"),
        )
    }
}

fun TripEntity.toConfirmPayload(driverRemoteId: String, firstTime: Boolean): ConfirmPayload? {
    val classification = when (purpose) {
        TripPurpose.BUSINESS_COMMUTE -> "COMMUTE"
        TripPurpose.BUSINESS_GENERAL -> "BUSINESS"
        TripPurpose.PERSONAL -> "PERSONAL"
        TripPurpose.UNCONFIRMED -> return null
    }
    return ConfirmPayload(
        uuid = uuid,
        expectedVersion = serverVersion,
        driverId = driverRemoteId,
        classification = classification,
        purposeText = if (classification == "BUSINESS") purposeText?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BUSINESS_PURPOSE else null,
        note = memo?.trim()?.takeIf { it.isNotEmpty() }?.take(1000),
        reason = if (firstTime) "차량에서 운전자 확인" else "차량에서 목적 변경",
    )
}

const val DEFAULT_BUSINESS_PURPOSE = "일반 업무"
