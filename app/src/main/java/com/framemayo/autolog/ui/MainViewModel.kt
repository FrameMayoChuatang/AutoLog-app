package com.framemayo.autolog.ui

import android.app.Application
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.framemayo.autolog.container
import com.framemayo.autolog.data.link.LinkResult
import com.framemayo.autolog.data.link.VehicleLink
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverProfile
import com.framemayo.autolog.data.local.PurposeEntity
import com.framemayo.autolog.data.remote.PurposeCatalog
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.sync.SyncScheduler
import com.framemayo.autolog.data.sync.TripSyncStatus
import com.framemayo.autolog.data.sync.syncStatus
import com.framemayo.autolog.service.RecorderState
import com.framemayo.autolog.service.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

data class MainUiState(
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val drivers: List<DriverEntity> = emptyList(),
    val activeTrip: TripEntity? = null,
    val recentTrips: List<TripEntity> = emptyList(),
    val consentPrompt: DriverEntity? = null,
    val profiles: List<DriverProfile> = emptyList(),
    val syncStatus: Map<Long, TripSyncStatus> = emptyMap(),
    val linked: Boolean = false,
    val profileNames: Map<Long, String> = emptyMap(),
) {
    fun driverName(id: Long?): String = profileNames[id]
        ?: profiles.find { it.driver.id == id }?.displayName
        ?: drivers.find { it.id == id }?.name
        ?: "운전자 없음"
}

data class LinkUiState(
    val link: VehicleLink? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val trips = app.container.trips
    private val drivers = app.container.drivers
    private val arrival = app.container.arrival

    private suspend fun consentOf(driverId: Long?): Boolean? = driverId?.let { drivers.consentOf(it) }

    private val consentPrompt = MutableStateFlow<DriverEntity?>(null)

    private val driverLists = combine(drivers.all, drivers.profiles, drivers.profileSettings) { all, profiles, settings ->
        Triple(all, profiles, settings.mapNotNull { p -> p.displayName?.let { p.driverId to it } }.toMap())
    }
    private var startingTrip = false
    private val vehicleLink = app.container.vehicleLink

    val state: StateFlow<MainUiState> = combine(
        RecorderState.snapshot, driverLists, trips.active, trips.recent, consentPrompt,
    ) { vehicle, driverList, active, recent, prompt ->
        MainUiState(vehicle, driverList.first, active, recent, prompt, driverList.second, profileNames = driverList.third)
    }.combine(vehicleLink.link) { ui, link ->
        val byId = ui.drivers.associateBy { it.id }
        ui.copy(
            syncStatus = ui.recentTrips.associate { it.id to it.syncStatus(it.driverId?.let(byId::get), link != null) },
            linked = link != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private val linkForm = MutableStateFlow(LinkUiState())

    val linkState: StateFlow<LinkUiState> = combine(vehicleLink.link, linkForm) { link, form -> form.copy(link = link) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkUiState(link = vehicleLink.link.value))

    fun connectVehicle(pairingCode: String) {
        if (linkForm.value.busy) return
        linkForm.value = linkForm.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = vehicleLink.connect(pairingCode)
            val error = when (result) {
                is LinkResult.Ok -> {
                    SyncScheduler.enqueue(getApplication())
                    null
                }
                LinkResult.InvalidCode -> "페어링 코드가 올바르지 않거나 만료되었습니다. 8자리 숫자를 확인하고, 안 되면 관리자에게 새 코드를 받아 주세요."
                is LinkResult.RateLimited -> "시도 횟수가 너무 많습니다. ${result.seconds?.let { s -> "${s}초" } ?: "잠시"} 뒤에 다시 시도해 주세요."
                LinkResult.Retry -> "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."
            }
            linkForm.value = linkForm.value.copy(busy = false, error = error)
        }
    }

    fun disconnectVehicle() {
        vehicleLink.disconnect()
        viewModelScope.launch {
            drivers.clearServerLinks()
            trips.clearServerState()
        }
    }

    fun addDriver(name: String, department: String) {
        if (name.isBlank()) return
        viewModelScope.launch { drivers.add(name, department) }
    }

    suspend fun saveProfile(id: Long?, name: String, color: Int, expression: Int) {
        check(RecorderState.snapshot.value.gear == VehicleGear.GEAR_PARK) { "Profile editing requires Park" }
        if (id == null) drivers.addProfile(name, color, expression) else drivers.editProfile(id, name, color, expression)
    }

    suspend fun deleteProfile(id: Long): Boolean {
        check(RecorderState.snapshot.value.gear == VehicleGear.GEAR_PARK) { "Profile deletion requires Park" }
        return drivers.hideProfile(id)
    }

    fun startTrip(driverId: Long?) {
        if (RecorderState.snapshot.value.gear != VehicleGear.GEAR_PARK || startingTrip || consentPrompt.value != null) return
        if (state.value.profiles.none { it.driver.id == driverId }) return
        val driver = state.value.drivers.find { it.id == driverId }
        if (driver != null && driver.locationConsent == null) {
            consentPrompt.value = driver
            return
        }
        beginTrip(driverId)
    }

    fun answerConsent(consent: Boolean) {
        val driver = consentPrompt.value ?: return
        consentPrompt.value = null
        viewModelScope.launch {
            drivers.setLocationConsent(driver.id, consent)
            beginTrip(driver.id)
        }
    }

    fun dismissConsent() {
        consentPrompt.value = null
    }

    fun setLocationConsent(driverId: Long, consent: Boolean) {
        viewModelScope.launch {
            drivers.setLocationConsent(driverId, consent)
            if (!consent) arrival.purgeDriver(driverId)
        }
    }

    private fun beginTrip(driverId: Long?) {
        val vehicle = RecorderState.snapshot.value
        if (vehicle.gear != VehicleGear.GEAR_PARK || vehicleLink.link.value == null || startingTrip) return
        val km = vehicle.odometerKm?.takeIf { it.isFinite() && it > 0f } ?: return
        startingTrip = true
        viewModelScope.launch {
            try { trips.start(driverId, km) } finally { startingTrip = false }
        }
    }

    fun endTrip() {
        val km = RecorderState.snapshot.value.odometerKm ?: return
        viewModelScope.launch {
            val ended = trips.end(km) ?: return@launch
            arrival.recordArrival(ended, consentOf(ended.driverId))
        }
    }

    fun assignDriver(tripId: Long, driverId: Long) {
        viewModelScope.launch { trips.setDriver(tripId, driverId) }
    }

    suspend fun loadPurposes(): List<PurposeEntity>? {
        val link = vehicleLink.link.value ?: return null
        val result = PurposeCatalog(getApplication<Application>().container.server).load()
        // 조회 도중 연결이 바뀌면 이전 회사의 목록을 보여주지 않는다.
        return result.takeIf { vehicleLink.link.value == link }
    }

    // 화면이 닫혀도 목적 저장 뒤의 위치정보 정리까지 완료해야 한다.
    suspend fun setPurpose(tripId: Long, purpose: TripPurpose, purposeText: String?) = viewModelScope.async {
        check(RecorderState.snapshot.value.gear == VehicleGear.GEAR_PARK) { "주차 후 수정해 주세요." }
        val previous = trips.get(tripId) ?: error("운행 기록을 찾을 수 없습니다.")
        check(previous.endedAt != null) { "종료된 운행만 수정할 수 있습니다." }
        trips.setPurpose(tripId, purpose, purposeText = purposeText)
        arrival.settle(tripId, consentOf(previous.driverId), purpose)
    }.await()
}
