package com.framemayo.autolog.data.link

import android.content.SharedPreferences
import com.framemayo.autolog.data.remote.ApiResult
import com.framemayo.autolog.data.remote.ServerApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class VehicleLink(
    val vehicleId: String,
    val registrationNumber: String,
    val model: String,
    val accessToken: String,
    val expiresAt: String,
    val connectedAt: Long,
)

sealed interface LinkResult {
    data class Ok(val link: VehicleLink) : LinkResult

    /** 토큰이 틀렸거나 만료·사용됨 */
    data object InvalidCode : LinkResult

    /** 서버에 닿지 못했거나 서버 오류 */
    data object Retry : LinkResult
}

sealed interface LinkResponse {
    data class Ok(
        val vehicleId: String,
        val registrationNumber: String,
        val model: String,
        val accessToken: String,
        val expiresAt: String,
    ) : LinkResponse

    data object InvalidCode : LinkResponse

    data object Retry : LinkResponse
}

interface VehicleLinkApi {
    suspend fun pair(pairingToken: String): LinkResponse
}

class HttpVehicleLinkApi(private val server: ServerApi) : VehicleLinkApi {
    override suspend fun pair(pairingToken: String): LinkResponse {
        val r = server.call("POST", "/api/device/pair", JSONObject().put("pairingToken", pairingToken), auth = false)
        if (r !is ApiResult.Http) return LinkResponse.Retry
        if (r.ok) {
            val json = r.json ?: return LinkResponse.Retry
            return runCatching {
                val vehicle = json.getJSONObject("vehicle")
                LinkResponse.Ok(
                    vehicleId = vehicle.getString("id"),
                    registrationNumber = vehicle.getString("registrationNumber"),
                    model = vehicle.optString("model"),
                    accessToken = json.getString("accessToken"),
                    expiresAt = json.optString("expiresAt"),
                )
            }.getOrDefault(LinkResponse.Retry)
        }
        return if (r.code in 400..499) LinkResponse.InvalidCode else LinkResponse.Retry
    }
}

class VehicleLinkRepository(
    private val prefs: SharedPreferences,
    private val api: VehicleLinkApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _link = MutableStateFlow(load())
    val link: StateFlow<VehicleLink?> = _link.asStateFlow()

    suspend fun connect(pairingToken: String): LinkResult {
        val token = pairingToken.trim()
        if (token.isEmpty()) return LinkResult.InvalidCode
        return when (val r = api.pair(token)) {
            LinkResponse.InvalidCode -> LinkResult.InvalidCode
            LinkResponse.Retry -> LinkResult.Retry
            is LinkResponse.Ok -> {
                val link = VehicleLink(r.vehicleId, r.registrationNumber, r.model, r.accessToken, r.expiresAt, clock())
                save(link)
                LinkResult.Ok(link)
            }
        }
    }

    fun disconnect() {
        prefs.edit().clear().commit()
        _link.value = null
    }

    private fun save(link: VehicleLink) {
        prefs.edit().clear()
            .putString(K_ID, link.vehicleId)
            .putString(K_NUMBER, link.registrationNumber)
            .putString(K_MODEL, link.model)
            .putString(K_TOKEN, link.accessToken)
            .putString(K_EXPIRES, link.expiresAt)
            .putLong(K_AT, link.connectedAt)
            .commit() // 연결 직후 종료돼도 남도록 동기 저장
        _link.value = link
    }

    private fun load(): VehicleLink? = VehicleLink(
        vehicleId = prefs.getString(K_ID, null) ?: return null,
        registrationNumber = prefs.getString(K_NUMBER, null) ?: return null,
        model = prefs.getString(K_MODEL, "") ?: "",
        accessToken = prefs.getString(K_TOKEN, null) ?: return null,
        expiresAt = prefs.getString(K_EXPIRES, "") ?: "",
        connectedAt = prefs.getLong(K_AT, 0),
    )

    private companion object {
        const val K_ID = "vehicleId"
        const val K_NUMBER = "registrationNumber"
        const val K_MODEL = "model"
        const val K_TOKEN = "accessToken"
        const val K_EXPIRES = "expiresAt"
        const val K_AT = "connectedAt"
    }
}
