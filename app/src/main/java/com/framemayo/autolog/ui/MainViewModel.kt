package com.framemayo.autolog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.framemayo.autolog.container
import com.framemayo.autolog.data.link.LinkResult
import com.framemayo.autolog.data.link.VehicleLink
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.service.RecorderState
import com.framemayo.autolog.service.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val drivers: List<DriverEntity> = emptyList(),
    val activeTrip: TripEntity? = null,
    val recentTrips: List<TripEntity> = emptyList(),
    val consentPrompt: DriverEntity? = null,
)

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

    val state: StateFlow<MainUiState> = combine(
        RecorderState.snapshot, drivers.all, trips.active, trips.recent, consentPrompt,
    ) { vehicle, driverList, active, recent, prompt ->
        MainUiState(vehicle, driverList, active, recent, prompt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private val vehicleLink = app.container.vehicleLink
    private val linkForm = MutableStateFlow(LinkUiState())

    val linkState: StateFlow<LinkUiState> = combine(vehicleLink.link, linkForm) { link, form -> form.copy(link = link) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkUiState(link = vehicleLink.link.value))

    fun connectVehicle(pairingCode: String) {
        if (linkForm.value.busy) return
        linkForm.value = linkForm.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = vehicleLink.connect(pairingCode)
            val error = when (result) {
                is LinkResult.Ok -> null
                LinkResult.InvalidCode -> "페어링 코드가 올바르지 않거나 만료되었습니다. 8자리 숫자를 확인하고, 안 되면 관리자에게 새 코드를 받아 주세요."
                is LinkResult.RateLimited -> "시도 횟수가 너무 많습니다. ${result.seconds?.let { s -> "${s}초" } ?: "잠시"} 뒤에 다시 시도해 주세요."
                LinkResult.Retry -> "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."
            }
            linkForm.value = linkForm.value.copy(busy = false, error = error)
        }
    }

    fun disconnectVehicle() = vehicleLink.disconnect()

    fun addDriver(name: String, department: String) {
        if (name.isBlank()) return
        viewModelScope.launch { drivers.add(name, department) }
    }

    fun startTrip(driverId: Long?) {
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
        if (vehicleLink.link.value == null) return
        val km = RecorderState.snapshot.value.odometerKm ?: return
        viewModelScope.launch { trips.start(driverId, km) }
    }

    fun endTrip() {
        val km = RecorderState.snapshot.value.odometerKm ?: return
        viewModelScope.launch {
            val ended = trips.end(km) ?: return@launch
            arrival.recordArrival(ended, consentOf(ended.driverId))
        }
    }

    fun setPurpose(tripId: Long, purpose: TripPurpose) {
        viewModelScope.launch {
            trips.setPurpose(tripId, purpose)
            val trip = trips.get(tripId) ?: return@launch
            arrival.settle(tripId, consentOf(trip.driverId), purpose)
        }
    }
}
