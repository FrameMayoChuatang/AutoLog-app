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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val trips = app.container.trips
    private val drivers = app.container.drivers

    val state: StateFlow<MainUiState> = combine(
        RecorderState.snapshot, drivers.all, trips.active, trips.recent,
    ) { vehicle, driverList, active, recent ->
        MainUiState(vehicle, driverList, active, recent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun addDriver(name: String, department: String) {
        if (name.isBlank()) return
        viewModelScope.launch { drivers.add(name, department) }
    }

    fun startTrip(driverId: Long?) {
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
