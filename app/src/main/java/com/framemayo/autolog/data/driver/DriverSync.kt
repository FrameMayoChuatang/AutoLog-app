package com.framemayo.autolog.data.driver

import android.util.Log
import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.remote.ApiResult
import com.framemayo.autolog.data.remote.ServerApi
import com.framemayo.autolog.data.repository.DriverRepository
import com.framemayo.autolog.data.repository.RemoteDriver
import org.json.JSONObject

sealed interface ApiOutcome<out T> {
    data class Ok<T>(val value: T) : ApiOutcome<T>

    data object Rejected : ApiOutcome<Nothing>

    data object Unauthorized : ApiOutcome<Nothing>

    data object Retry : ApiOutcome<Nothing>
}

interface DriverApi {
    suspend fun list(): ApiOutcome<List<RemoteDriver>>

    suspend fun register(name: String, department: String): ApiOutcome<RemoteDriver>

    suspend fun sendConsent(remoteId: String, granted: Boolean, noticeVersion: String): ApiOutcome<Unit>
}

class HttpDriverApi(private val server: ServerApi) : DriverApi {
    override suspend fun list(): ApiOutcome<List<RemoteDriver>> = when (val r = server.call("GET", "/api/device/drivers")) {
        !is ApiResult.Http -> ApiOutcome.Retry
        else -> r.outcome { http ->
            val array = http.array ?: return@outcome ApiOutcome.Retry
            val drivers = (0 until array.length()).map { array.optJSONObject(it)?.toRemoteDriver() ?: return@outcome ApiOutcome.Retry }
            ApiOutcome.Ok(drivers)
        }
    }

    override suspend fun register(name: String, department: String): ApiOutcome<RemoteDriver> {
        val body = JSONObject().put("name", name).put("department", department)
        val r = server.call("POST", "/api/device/drivers", body)
        if (r !is ApiResult.Http) return ApiOutcome.Retry
        return r.outcome { http ->
            http.json?.toRemoteDriver()?.let { ApiOutcome.Ok(it) } ?: ApiOutcome.Retry
        }
    }

    override suspend fun sendConsent(remoteId: String, granted: Boolean, noticeVersion: String): ApiOutcome<Unit> {
        val body = JSONObject().put("granted", granted).put("noticeVersion", noticeVersion)
        val r = server.call("POST", "/api/device/drivers/$remoteId/consent", body)
        if (r !is ApiResult.Http) return ApiOutcome.Retry
        return r.outcome { ApiOutcome.Ok(Unit) }
    }

    private inline fun <T> ApiResult.Http.outcome(onOk: (ApiResult.Http) -> ApiOutcome<T>): ApiOutcome<T> = when {
        ok -> onOk(this)
        code == 401 -> ApiOutcome.Unauthorized
        code == 400 || code == 404 || code == 409 -> ApiOutcome.Rejected
        else -> ApiOutcome.Retry
    }

    private fun JSONObject.toRemoteDriver(): RemoteDriver? = runCatching {
        RemoteDriver(
            remoteId = getString("id"),
            name = getString("name"),
            department = optString("department"),
            status = parseStatus(optString("status")),
        )
    }.getOrNull()

    private fun parseStatus(value: String): DriverStatus =
        runCatching { DriverStatus.valueOf(value) }.getOrDefault(DriverStatus.PENDING)
}

class DriverSyncManager(
    private val drivers: DriverRepository,
    private val api: DriverApi,
    private val isLinked: () -> Boolean = { true },
) {
    suspend fun sync(): Boolean {
        if (!isLinked()) return true
        val server = when (val r = api.list()) {
            is ApiOutcome.Ok -> r.value
            ApiOutcome.Unauthorized -> return true
            ApiOutcome.Rejected, ApiOutcome.Retry -> return false
        }
        linkUniqueMatches(server)
        drivers.upsertRemote(server)
        registerRest()?.let { return it }
        return sendConsents()
    }

    private suspend fun linkUniqueMatches(server: List<RemoteDriver>) {
        val locals = drivers.snapshot()
        val taken = locals.mapNotNull { it.remoteId }.toMutableSet()
        val unlinked = locals.filter { it.remoteId == null }
        for (local in unlinked) {
            val key = key(local.name, local.department)
            if (unlinked.count { key(it.name, it.department) == key } != 1) continue
            val candidates = server.filter { it.remoteId !in taken && key(it.name, it.department) == key }
            val match = candidates.singleOrNull() ?: continue
            drivers.link(local.id, match)
            taken += match.remoteId
        }
    }

    private suspend fun registerRest(): Boolean? {
        for (local in drivers.snapshot().filter { it.remoteId == null }) {
            val name = local.name.trim().take(MAX_NAME)
            val department = local.department.trim().ifBlank { NO_DEPARTMENT }.take(MAX_DEPARTMENT)
            when (val r = api.register(name, department)) {
                is ApiOutcome.Ok -> drivers.link(local.id, r.value)
                ApiOutcome.Retry -> return false
                ApiOutcome.Unauthorized -> return true
                ApiOutcome.Rejected -> Log.w(TAG, "driver ${local.id} registration rejected")
            }
        }
        return null
    }

    private suspend fun sendConsents(): Boolean {
        for (d in drivers.consentPending()) {
            val remoteId = d.remoteId ?: continue
            val granted = d.locationConsent ?: continue
            when (api.sendConsent(remoteId, granted, NOTICE_VERSION)) {
                is ApiOutcome.Ok -> drivers.markConsentSent(d)
                ApiOutcome.Retry -> return false
                ApiOutcome.Unauthorized -> return true
                ApiOutcome.Rejected -> drivers.markConsentSent(d)
            }
        }
        return true
    }

    private fun key(name: String, department: String) =
        name.trim().lowercase().replace(Regex("\\s+"), " ") + "|" + department.trim().ifBlank { NO_DEPARTMENT }.lowercase().replace(Regex("\\s+"), " ")

    companion object {
        const val NOTICE_VERSION = "v1"
        const val NO_DEPARTMENT = "미지정"
        private const val MAX_NAME = 80
        private const val MAX_DEPARTMENT = 100
        private const val TAG = "DriverSyncManager"
    }
}
