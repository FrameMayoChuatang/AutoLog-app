package com.framemayo.autolog.data.link

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VehicleLink(
    val vehicleRemoteId: Long,
    val companyName: String,
    val vehicleNumber: String,
    val vin: String?,
    val connectedAt: Long,
)

sealed interface LinkResult {
    data class Ok(val link: VehicleLink) : LinkResult

    data object InvalidCode : LinkResult

    data object InvalidNumber : LinkResult

    data object Retry : LinkResult
}

sealed interface LinkResponse {
    data class Ok(val vehicleRemoteId: Long, val companyName: String) : LinkResponse

    data object InvalidCode : LinkResponse

    data object Retry : LinkResponse
}

interface VehicleLinkApi {
    suspend fun connect(code: String, vehicleNumber: String, vin: String?): LinkResponse
}

class FakeVehicleLinkApi : VehicleLinkApi {
    @Volatile var online = true

    override suspend fun connect(code: String, vehicleNumber: String, vin: String?): LinkResponse = when {
        !online -> LinkResponse.Retry
        code.length == 6 && code.all(Char::isDigit) -> LinkResponse.Ok(vehicleRemoteId = 1, companyName = "테스트 회사")
        else -> LinkResponse.InvalidCode
    }
}

class VehicleLinkRepository(
    private val prefs: SharedPreferences,
    private val api: VehicleLinkApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _link = MutableStateFlow(load())
    val link: StateFlow<VehicleLink?> = _link.asStateFlow()

    suspend fun connect(code: String, vehicleNumber: String, vin: String?): LinkResult {
        val number = vehicleNumber.filterNot(Char::isWhitespace)
        if (!PLATE.matches(number)) return LinkResult.InvalidNumber
        val trimmedCode = code.trim()
        if (trimmedCode.isEmpty()) return LinkResult.InvalidCode
        return when (val r = api.connect(trimmedCode, number, vin)) {
            LinkResponse.InvalidCode -> LinkResult.InvalidCode
            LinkResponse.Retry -> LinkResult.Retry
            is LinkResponse.Ok -> {
                val link = VehicleLink(r.vehicleRemoteId, r.companyName, number, vin, clock())
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
        prefs.edit()
            .putLong(K_ID, link.vehicleRemoteId)
            .putString(K_COMPANY, link.companyName)
            .putString(K_NUMBER, link.vehicleNumber)
            .putString(K_VIN, link.vin)
            .putLong(K_AT, link.connectedAt)
            .commit() // 연결 직후 종료돼도 남도록 동기 저장
        _link.value = link
    }

    private fun load(): VehicleLink? {
        if (!prefs.contains(K_ID)) return null
        return VehicleLink(
            vehicleRemoteId = prefs.getLong(K_ID, 0),
            companyName = prefs.getString(K_COMPANY, null) ?: return null,
            vehicleNumber = prefs.getString(K_NUMBER, null) ?: return null,
            vin = prefs.getString(K_VIN, null),
            connectedAt = prefs.getLong(K_AT, 0),
        )
    }

    private companion object {
        val PLATE = Regex("^([가-힣]{2})?\\d{2,3}[가-힣]\\d{4}$")
        const val K_ID = "vehicleRemoteId"
        const val K_COMPANY = "companyName"
        const val K_NUMBER = "vehicleNumber"
        const val K_VIN = "vin"
        const val K_AT = "connectedAt"
    }
}
