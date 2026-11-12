package com.framemayo.autolog.service

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 차량 움직임 판단. PARKED = 기어 P, IDLE = P가 아닌데 누적거리 무변화(신호 대기 등), DRIVING = 그 외. */
enum class MotionState { UNKNOWN, DRIVING, IDLE, PARKED }

/** 차량 SDK 수집 상태. RETRYING이면 초기화·구독을 백오프로 재시도 중. */
enum class CollectorStatus { STOPPED, STARTING, RUNNING, RETRYING }

data class VehicleSnapshot(
    val serviceRunning: Boolean = false,
    val odometerKm: Float? = null,
    val odometerUpdatedAt: Long = 0L,
    val gear: VehicleGear? = null,
    val stationaryMs: Long = 0L,
    val stopCandidate: Boolean = false,
    val motion: MotionState = MotionState.UNKNOWN,
    /** 운행 중 주차가 길어져 종료를 안내할 시점(B가 종료 안내에 사용). */
    val endCandidate: Boolean = false,
    val fuelPercent: Float? = null,
    val batteryPercent: Float? = null,
    val chargePortOpen: Boolean? = null,
    val collector: CollectorStatus = CollectorStatus.STOPPED,
) {
    val isParked: Boolean get() = gear == null || gear == VehicleGear.GEAR_PARK
}

object RecorderState {
    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    internal fun update(block: (VehicleSnapshot) -> VehicleSnapshot) = _snapshot.update(block)
}
