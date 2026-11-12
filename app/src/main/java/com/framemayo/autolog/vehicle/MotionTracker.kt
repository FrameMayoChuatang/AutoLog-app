package com.framemayo.autolog.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.framemayo.autolog.service.MotionState

/** 정차 판단이 요청하는 부수 효과. 서비스가 저장소에 순서대로 적용한다. */
sealed interface MotionAction {
    /** 운행 중 P 구간 시작. [startedAt]·[odometerKm]은 P를 처음 본 시각과 그때의 누적거리(실측). */
    data class OpenStop(val tripId: Long, val startedAt: Long, val odometerKm: Float) : MotionAction

    /** 열린 P 구간 종료. */
    data class CloseStop(val tripId: Long, val endedAt: Long) : MotionAction

    /** P 상태로 누적거리 변화 없이 오래 머물러 운행을 자동 종료. 값은 주차 시작 시점의 실측치. */
    data class AutoEnd(val tripId: Long, val odometerKm: Float, val endedAt: Long) : MotionAction
}

/** 추적기가 알아야 하는 진행 중 운행. [resumedStop]은 DB에 이미 열려 있던 P 구간(서비스 재시작 시). */
data class ActiveTrip(val id: Long, val startedAt: Long, val resumedStop: ResumedStop? = null)

data class ResumedStop(val startedAt: Long, val odometerKm: Float)

data class MotionStatus(
    val motion: MotionState = MotionState.UNKNOWN,
    val endCandidate: Boolean = false,
    /** 누적거리가 마지막으로 바뀐 뒤 흐른 시간(기어 무관). */
    val stationaryMs: Long = 0L,
    /** 누적거리 무변화가 정차 기준([MotionTracker] idleThresholdMs) 이상. 기존 StopDetector 의미. */
    val stopCandidate: Boolean = false,
)

/**
 * 기어·누적거리로 움직임을 판단하고 운행 중 P 구간을 기록하는 결정적 상태 기계.
 *
 * - PARKED: 기어가 명시적으로 [VehicleGear.GEAR_PARK].
 * - IDLE: P가 아니고 누적거리가 [idleThresholdMs] 이상 그대로(신호 대기 등). 저장하지 않는다.
 * - DRIVING: 그 외. 누적거리를 아직 모르면 UNKNOWN.
 *
 * 기어 null·[VehicleGear.GEAR_UNKNOWN]은 "정보 없음"으로 보고 마지막으로 알던 기어를 유지한다.
 * [com.framemayo.autolog.service.VehicleSnapshot.isParked]는 null을 P로 취급하지만(운전자 전환 등 안전한 기본값),
 * 여기서는 명시적인 P만 PARKED로 판단해 P 구간 기록·종료 후보·자동 종료가 실제 P에서만 일어나게 한다.
 * 기어를 끝내 모르면 누적거리만으로 DRIVING/IDLE을 판단한다.
 *
 * P 구간은 운행이 시작된 뒤 시작된 것만 기록한다. P 상태에서 운행을 시작하면(출발 전 대기) 기록·종료 안내·
 * 자동 종료 대상이 아니며, P를 풀었다가 다시 넣어야 한다. 누적거리를 모르는 채 P가 되면 첫 누적거리가
 * 들어올 때 P 시각으로 구간을 연다.
 *
 * P 중 누적거리가 바뀌면(잘못된 데이터 또는 실제 이동) 종료 안내·자동 종료 타이머의 기준을 그 시각·값으로
 * 다시 잡는다. 이미 연 구간 행의 시작값은 바꾸지 않는다.
 *
 * 자동 종료 시 열린 구간은 운행 종료 시각(= 마지막 실측 기준 시각)으로 닫는다([MotionAction.CloseStop]이
 * [MotionAction.AutoEnd]보다 먼저 나온다). 정상적인 경우 길이 0인 구간이 남는다.
 *
 * 스레드 안전하지 않다. 한 코루틴(또는 Mutex) 안에서만 호출한다.
 */
class MotionTracker(
    private val idleThresholdMs: Long = 45_000L,
    private val endCandidateMs: Long = 5 * 60_000L,
    private val autoEndMs: Long = 30 * 60_000L,
) {
    private var gear: VehicleGear? = null
    private var lastKm: Float? = null
    private var lastKmChangedAt: Long = 0L
    private var leftParkAt: Long = 0L

    private var tripId: Long? = null
    private var autoEndedTripId: Long? = null

    /** 현재 P 구간을 처음 본 시각. P가 아니면 null. */
    private var parkStartedAt: Long? = null
    /** 이 P 구간이 운행 중 구간으로 기록·판단 대상인지. */
    private var parkCounts = false
    private var stopOpen = false
    /** 종료 안내·자동 종료 타이머 기준(주차 시작 또는 P 중 마지막 누적거리 변화). */
    private var anchorAt: Long = 0L
    private var anchorKm: Float? = null
    private var autoEnded = false

    private var now: Long = 0L

    val status: MotionStatus get() = statusAt(now)

    fun statusAt(at: Long): MotionStatus {
        val stationary = if (lastKm == null) 0L else (at - lastKmChangedAt).coerceAtLeast(0L)
        return MotionStatus(
            motion = motionAt(at),
            endCandidate = endCandidateAt(at),
            stationaryMs = stationary,
            stopCandidate = lastKm != null && stationary >= idleThresholdMs,
        )
    }

    fun onActiveTrip(trip: ActiveTrip?, now: Long): List<MotionAction> {
        this.now = now
        if (trip?.id == tripId) return emptyList()
        if (trip != null && trip.id == autoEndedTripId) return emptyList()
        val actions = mutableListOf<MotionAction>()
        closeOpenStop(now, actions)
        tripId = trip?.id
        parkCounts = false
        autoEnded = false
        if (trip == null) return actions

        val resumed = trip.resumedStop
        if (resumed != null) {
            if (isParkedGear() || gear == null) {
                // 재시작 전부터 열려 있던 P 구간을 이어받는다.
                parkStartedAt = resumed.startedAt
                anchorAt = resumed.startedAt
                anchorKm = resumed.odometerKm
                parkCounts = true
                stopOpen = true
                lastKm?.let { km -> if (km != resumed.odometerKm) { anchorAt = now; anchorKm = km } }
                if (lastKm == null) { lastKm = resumed.odometerKm; lastKmChangedAt = resumed.startedAt }
            } else {
                actions += MotionAction.CloseStop(trip.id, now.coerceAtLeast(resumed.startedAt))
            }
        } else {
            val parkStart = parkStartedAt
            if (isParkedGear() && parkStart != null && parkStart > trip.startedAt) {
                parkCounts = true
                maybeOpenStop(actions)
            }
        }
        evaluate(now, actions)
        return actions
    }

    fun onGear(gear: VehicleGear?, now: Long): List<MotionAction> {
        this.now = now
        if (gear == null || gear == VehicleGear.GEAR_UNKNOWN) return emptyList()
        val wasParked = isParkedGear()
        this.gear = gear
        val actions = mutableListOf<MotionAction>()
        val parked = gear == VehicleGear.GEAR_PARK
        if (parked && !wasParked) {
            if (parkStartedAt == null) {
                parkStartedAt = now
                anchorAt = now
                anchorKm = lastKm
                parkCounts = tripId != null
                autoEnded = false
                maybeOpenStop(actions)
            }
            // parkStartedAt이 이미 있으면 재시작 때 이어받은 구간이 기어 확인을 기다리던 중이다.
        } else if (!parked && (wasParked || parkStartedAt != null)) {
            closeOpenStop(now, actions)
            parkStartedAt = null
            parkCounts = false
            anchorKm = null
            leftParkAt = now
        }
        evaluate(now, actions)
        return actions
    }

    /** [km]은 이미 [OdometerValidator]로 검증된 값. */
    fun onOdometer(km: Float, now: Long): List<MotionAction> {
        this.now = now
        if (lastKm == null || km != lastKm) {
            lastKm = km
            lastKmChangedAt = now
        }
        val actions = mutableListOf<MotionAction>()
        if (parkStartedAt != null) {
            val anchored = anchorKm
            if (anchored == null) {
                // P를 본 뒤 첫 누적거리: 기준 시각은 P 시각 그대로.
                anchorKm = km
                maybeOpenStop(actions)
            } else if (km != anchored) {
                anchorAt = now
                anchorKm = km
            }
        }
        evaluate(now, actions)
        return actions
    }

    fun onTick(now: Long): List<MotionAction> {
        this.now = now
        val actions = mutableListOf<MotionAction>()
        evaluate(now, actions)
        return actions
    }

    private fun isParkedGear() = gear == VehicleGear.GEAR_PARK

    private fun motionAt(at: Long): MotionState = when {
        isParkedGear() -> MotionState.PARKED
        lastKm == null -> MotionState.UNKNOWN
        at - maxOf(lastKmChangedAt, leftParkAt) >= idleThresholdMs -> MotionState.IDLE
        else -> MotionState.DRIVING
    }

    private fun endCandidateAt(at: Long): Boolean =
        isParkedGear() && parkCounts && tripId != null && !autoEnded && anchorKm != null &&
            at - anchorAt >= endCandidateMs

    private fun maybeOpenStop(actions: MutableList<MotionAction>) {
        val id = tripId ?: return
        val start = parkStartedAt ?: return
        val km = anchorKm ?: return
        if (!parkCounts || stopOpen) return
        stopOpen = true
        actions += MotionAction.OpenStop(id, start, km)
    }

    private fun closeOpenStop(at: Long, actions: MutableList<MotionAction>) {
        val id = tripId
        if (stopOpen && id != null) actions += MotionAction.CloseStop(id, at.coerceAtLeast(parkStartedAt ?: at))
        stopOpen = false
    }

    private fun evaluate(at: Long, actions: MutableList<MotionAction>) {
        val id = tripId ?: return
        val km = anchorKm ?: return
        if (!isParkedGear() || !parkCounts || autoEnded) return
        if (at - anchorAt < autoEndMs) return
        if (stopOpen) actions += MotionAction.CloseStop(id, anchorAt)
        stopOpen = false
        actions += MotionAction.AutoEnd(id, km, anchorAt)
        autoEnded = true
        autoEndedTripId = id
        tripId = null
        parkCounts = false
    }
}
