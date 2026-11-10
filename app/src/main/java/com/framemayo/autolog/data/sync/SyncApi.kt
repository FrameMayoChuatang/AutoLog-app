package com.framemayo.autolog.data.sync

import com.framemayo.autolog.data.local.TripEntity

data class TripPayload(
    val uuid: String,
    val driverRemoteId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val startOdometerKm: Float,
    val endOdometerKm: Float?,
    val status: String,
    val purpose: String,
    val memo: String?,
    val updatedAt: Long,
)

sealed interface SyncResult {
    data object Ok : SyncResult

    data object Retry : SyncResult

    data object Rejected : SyncResult
}

interface SyncApi {
    suspend fun upload(trip: TripPayload): SyncResult
}

fun TripEntity.toPayload(driverRemoteId: String?) = TripPayload(
    uuid = uuid,
    driverRemoteId = driverRemoteId,
    startedAt = startedAt,
    endedAt = endedAt,
    startOdometerKm = startOdometerKm,
    endOdometerKm = endOdometerKm,
    status = status.name,
    purpose = purpose.name,
    memo = memo,
    updatedAt = updatedAt,
)
