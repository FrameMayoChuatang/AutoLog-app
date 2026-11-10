package com.framemayo.autolog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.framemayo.autolog.container
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

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val trips = app.container.trips
    private val drivers = app.container.drivers

    private val consentPrompt = MutableStateFlow<DriverEntity?>(null)

    val state: StateFlow<MainUiState> = combine(
        RecorderState.snapshot, drivers.all, trips.active, trips.recent, consentPrompt,
    ) { vehicle, driverList, active, recent, prompt ->
        MainUiState(vehicle, driverList, active, recent, prompt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

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
        viewModelScope.launch { drivers.setLocationConsent(driverId, consent) }
    }

    private fun beginTrip(driverId: Long?) {
        val km = RecorderState.snapshot.value.odometerKm ?: return
        viewModelScope.launch { trips.start(driverId, km) }
    }

    fun endTrip() {
        val km = RecorderState.snapshot.value.odometerKm ?: return
        viewModelScope.launch { trips.end(km) }
    }

    fun setPurpose(tripId: Long, purpose: TripPurpose) {
        viewModelScope.launch { trips.setPurpose(tripId, purpose) }
    }
}
