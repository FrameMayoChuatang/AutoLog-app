package com.framemayo.autolog.data.sync

import com.framemayo.autolog.data.remote.ApiResult
import com.framemayo.autolog.data.remote.ServerApi
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

class HttpSyncApi(private val server: ServerApi) : SyncApi {
    override suspend fun upload(trip: TripPayload): SyncResult {
        val r = server.call("PUT", "/api/device/trips/${trip.uuid}", trip.toJson())
        if (r !is ApiResult.Http) return SyncResult.Retry
        return when {
            r.ok -> r.json?.optLong("serverVersion", -1)?.takeIf { it >= 0 }?.let { SyncResult.Ok(it) } ?: SyncResult.Retry
            r.code == 409 -> SyncResult.Conflict
            r.code == 401 -> SyncResult.Unauthorized
            r.code == 400 || r.code == 404 -> SyncResult.Rejected
            else -> SyncResult.Retry
        }
    }

    override suspend fun fetch(uuid: String): FetchResult {
        val r = server.call("GET", "/api/device/trips/$uuid")
        if (r !is ApiResult.Http) return FetchResult.Retry
        return when {
            r.ok -> {
                val json = r.json ?: return FetchResult.Retry
                if (!json.has("serverVersion") || !json.has("sourceRevision")) return FetchResult.Retry
                FetchResult.Found(
                    serverVersion = json.getLong("serverVersion"),
                    sourceRevision = json.getLong("sourceRevision"),
                    locked = !json.isNull("measurementLockedAt"),
                )
            }
            r.code == 404 -> FetchResult.NotFound
            r.code == 401 -> FetchResult.Unauthorized
            else -> FetchResult.Retry
        }
    }
}

internal fun TripPayload.toJson(): JSONObject = JSONObject().apply {
    put("sourceRevision", sourceRevision)
    put("baseServerVersion", baseServerVersion)
    driverId?.let { put("driverId", it) }
    put("startedAt", instant(startedAt))
    endedAt?.let { put("endedAt", instant(it)) }
    lastObservedAt?.let { put("lastObservedAt", instant(it)) }
    startOdometerKm?.let { put("startOdometerKm", km(it)) }
    endOdometerKm?.let { put("endOdometerKm", km(it)) }
    lastOdometerKm?.let { put("lastOdometerKm", km(it)) }
    put("state", state)
    put("measurementSource", "PLEOS_ODOMETER")
    collectionIssues?.let { put("collectionIssues", JSONArray(it)) }
}

private fun instant(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

private fun km(value: Float): BigDecimal = BigDecimal(value.toString()).setScale(3, RoundingMode.HALF_UP)
