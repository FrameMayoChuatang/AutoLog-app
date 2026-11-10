package com.framemayo.autolog.service

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VehicleSnapshot(
    val serviceRunning: Boolean = false,
    val odometerKm: Float? = null,
    val odometerUpdatedAt: Long = 0L,
    val gear: VehicleGear? = null,
    val stationaryMs: Long = 0L,
    val stopCandidate: Boolean = false,
) {
    val isParked: Boolean get() = gear == null || gear == VehicleGear.GEAR_PARK
}

object RecorderState {
    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    internal fun update(block: (VehicleSnapshot) -> VehicleSnapshot) = _snapshot.update(block)
}
