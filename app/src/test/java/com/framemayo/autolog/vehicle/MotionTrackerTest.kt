package com.framemayo.autolog.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.framemayo.autolog.service.MotionState
import com.framemayo.autolog.vehicle.MotionAction.CloseStop
import com.framemayo.autolog.vehicle.MotionAction.OpenStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackerTest {
    private val s = 1_000L
    private val min = 60 * s
    private val tracker = MotionTracker()
    private val trip = ActiveTrip(id = 7L, startedAt = 0L)

    /** 운행 시작 → D로 주행 중인 상태. */
    private fun driving(km: Float = 100f, at: Long = 1 * s) {
        tracker.onActiveTrip(trip, 0L)
        tracker.onGear(VehicleGear.GEAR_DRIVE, at)
        tracker.onOdometer(km, at)
    }

    /** [from]부터 [to]까지 1초마다 틱을 주고 나온 동작을 모은다. */
    private fun tickRange(from: Long, to: Long): List<MotionAction> =
        (from..to step s).flatMap { tracker.onTick(it) }

    @Test
    fun `odometer unchanged without P is IDLE and not stored`() {
        driving()
        assertEquals(MotionState.DRIVING, tracker.statusAt(30 * s).motion)
        val actions = tickRange(2 * s, 60 * s)
        assertTrue(actions.isEmpty())
        assertEquals(MotionState.IDLE, tracker.status.motion)
        assertTrue(tracker.status.stopCandidate)
        tracker.onOdometer(100.1f, 61 * s)
        assertEquals(MotionState.DRIVING, tracker.status.motion)
    }

    @Test
    fun `gear P is PARKED immediately and opens a stop at park time and km`() {
        driving()
        tracker.onOdometer(103f, 10 * s)
        val actions = tracker.onGear(VehicleGear.GEAR_PARK, 20 * s)
        assertEquals(listOf(OpenStop(7L, 20 * s, 103f)), actions)
        assertEquals(MotionState.PARKED, tracker.status.motion)
    }

    @Test
    fun `short stop closes and returns to driving`() {
        driving()
        tracker.onGear(VehicleGear.GEAR_PARK, 10 * s)
        val actions = tracker.onGear(VehicleGear.GEAR_DRIVE, 40 * s)
        assertEquals(listOf(CloseStop(7L, 40 * s)), actions)
        // P를 막 푼 직후는 누적거리가 오래 그대로여도 DRIVING.
        assertEquals(MotionState.DRIVING, tracker.statusAt(41 * s).motion)
        assertEquals(MotionState.IDLE, tracker.statusAt(40 * s + 45 * s).motion)
    }

    @Test
    fun `multiple stops in one trip each open and close`() {
        driving()
        val all = mutableListOf<MotionAction>()
        all += tracker.onGear(VehicleGear.GEAR_PARK, 10 * s)
        all += tracker.onGear(VehicleGear.GEAR_DRIVE, 70 * s)
        all += tracker.onOdometer(105f, 5 * min)
        all += tracker.onGear(VehicleGear.GEAR_PARK, 6 * min)
        all += tracker.onGear(VehicleGear.GEAR_REVERSE, 8 * min)
        assertEquals(
            listOf(
                OpenStop(7L, 10 * s, 100f), CloseStop(7L, 70 * s),
                OpenStop(7L, 6 * min, 105f), CloseStop(7L, 8 * min),
            ),
            all,
        )
    }

    @Test
    fun `endCandidate after 5 minutes parked and reset when moving`() {
        driving()
        tracker.onGear(VehicleGear.GEAR_PARK, 10 * s)
        assertFalse(tracker.statusAt(10 * s + 5 * min - 1).endCandidate)
        tracker.onTick(10 * s + 5 * min)
        assertTrue(tracker.status.endCandidate)
        tracker.onGear(VehicleGear.GEAR_DRIVE, 10 * min)
        assertFalse(tracker.status.endCandidate)
    }

    @Test
    fun `endCandidate resets when trip ends manually and stop is closed`() {
        driving()
        tracker.onGear(VehicleGear.GEAR_PARK, 10 * s)
        tracker.onTick(6 * min)
        assertTrue(tracker.status.endCandidate)
        val actions = tracker.onActiveTrip(null, 7 * min)
        assertEquals(listOf(CloseStop(7L, 7 * min)), actions)
        assertFalse(tracker.status.endCandidate)
        // 운행이 없으니 이후 아무 동작도 없다.
        assertTrue(tickRange(7 * min, 40 * min).isEmpty())
    }

    @Test
    fun `long park never ends the trip and keeps only the end hint`() {
        // 기능 명세(정차 판단): 정차 후보 표시일 뿐 자동 종료 판단이 아니다.
        driving()
        tracker.onOdometer(112.5f, 2 * min)
        tracker.onGear(VehicleGear.GEAR_PARK, 3 * min)
        assertTrue(tickRange(3 * min, 10 * 60 * min).isEmpty())
        assertTrue(tracker.status.endCandidate)
        assertEquals(MotionState.PARKED, tracker.status.motion)
        // 운전자가 종료하면 그때 정차 구간을 닫는다.
        assertEquals(listOf(CloseStop(7L, 10 * 60 * min + 1)), tracker.onActiveTrip(null, 10 * 60 * min + 1))
    }

    @Test
    fun `odometer change while in P resets the end hint timer`() {
        driving()
        tracker.onGear(VehicleGear.GEAR_PARK, 1 * min)
        tracker.onTick(6 * min)
        assertTrue(tracker.status.endCandidate)
        // P인데 누적거리가 바뀜(잘못된 데이터): 타이머 기준을 다시 잡는다.
        assertTrue(tracker.onOdometer(100.4f, 20 * min).isEmpty())
        assertFalse(tracker.status.endCandidate)
        assertFalse(tracker.statusAt(24 * min).endCandidate)
        assertTrue(tracker.statusAt(25 * min).endCandidate)
        assertTrue(tracker.onTick(50 * min).isEmpty())
    }

    @Test
    fun `no stops recorded without an active trip`() {
        tracker.onGear(VehicleGear.GEAR_DRIVE, 0L)
        tracker.onOdometer(100f, 0L)
        val actions = mutableListOf<MotionAction>()
        actions += tracker.onGear(VehicleGear.GEAR_PARK, 10 * s)
        actions += tickRange(10 * s, 40 * min)
        actions += tracker.onGear(VehicleGear.GEAR_DRIVE, 41 * min)
        assertTrue(actions.isEmpty())
        assertFalse(tracker.statusAt(20 * min).endCandidate)
    }

    @Test
    fun `trip started while already in P does not record that park`() {
        tracker.onOdometer(100f, 0L)
        tracker.onGear(VehicleGear.GEAR_PARK, 0L)
        val actions = mutableListOf<MotionAction>()
        actions += tracker.onActiveTrip(ActiveTrip(id = 9L, startedAt = 5 * s), 5 * s)
        actions += tickRange(5 * s, 40 * min)
        assertTrue(actions.isEmpty())
        assertFalse(tracker.status.endCandidate)
        // P를 풀었다 다시 넣으면 기록한다.
        actions += tracker.onGear(VehicleGear.GEAR_DRIVE, 41 * min)
        actions += tracker.onGear(VehicleGear.GEAR_PARK, 42 * min)
        assertEquals(listOf(OpenStop(9L, 42 * min, 100f)), actions)
    }

    @Test
    fun `P observed after trip start (gear arrives late) is recorded regardless of order`() {
        tracker.onOdometer(100f, 10 * s)
        tracker.onGear(VehicleGear.GEAR_PARK, 10 * s)
        val actions = tracker.onActiveTrip(ActiveTrip(id = 3L, startedAt = 0L), 11 * s)
        assertEquals(listOf(OpenStop(3L, 10 * s, 100f)), actions)
    }

    @Test
    fun `P with unknown odometer opens stop on first reading at park time`() {
        tracker.onActiveTrip(trip, 0L)
        assertTrue(tracker.onGear(VehicleGear.GEAR_PARK, 10 * s).isEmpty())
        assertEquals(listOf(OpenStop(7L, 10 * s, 88f)), tracker.onOdometer(88f, 20 * s))
    }

    @Test
    fun `unknown gear keeps last known gear`() {
        driving()
        tracker.onGear(VehicleGear.GEAR_PARK, 10 * s)
        assertTrue(tracker.onGear(null, 20 * s).isEmpty())
        assertTrue(tracker.onGear(VehicleGear.GEAR_UNKNOWN, 30 * s).isEmpty())
        assertEquals(MotionState.PARKED, tracker.status.motion)
        assertEquals(listOf(CloseStop(7L, 40 * s)), tracker.onGear(VehicleGear.GEAR_DRIVE, 40 * s))
    }

    @Test
    fun `motion is UNKNOWN until gear or odometer is known and never PARKED without explicit P`() {
        assertEquals(MotionState.UNKNOWN, tracker.statusAt(0L).motion)
        tracker.onOdometer(100f, 0L)
        assertEquals(MotionState.DRIVING, tracker.statusAt(1 * s).motion)
        assertEquals(MotionState.IDLE, tracker.statusAt(45 * s).motion)
    }

    @Test
    fun `trip change closes the old trip's stop`() {
        driving()
        tracker.onGear(VehicleGear.GEAR_PARK, 10 * s)
        val actions = tracker.onActiveTrip(ActiveTrip(id = 8L, startedAt = 2 * min), 2 * min)
        // 새 운행은 P 중 시작했으니 이 P 구간은 새 운행에 기록하지 않는다.
        assertEquals(listOf(CloseStop(7L, 2 * min)), actions)
    }

    @Test
    fun `same active trip re-emitted is a no-op`() {
        driving()
        tracker.onGear(VehicleGear.GEAR_PARK, 10 * s)
        assertTrue(tracker.onActiveTrip(trip, 20 * s).isEmpty())
        assertTrue(tracker.onActiveTrip(trip.copy(startedAt = 0L), 30 * s).isEmpty())
    }

    @Test
    fun `resumed open stop keeps its start for the end hint after restart`() {
        val resumed = ActiveTrip(id = 7L, startedAt = 0L, resumedStop = ResumedStop(startedAt = 5 * min, odometerKm = 120f))
        assertTrue(tracker.onActiveTrip(resumed, 20 * min).isEmpty())
        assertTrue(tracker.onGear(VehicleGear.GEAR_PARK, 20 * min).isEmpty())
        assertTrue(tracker.onOdometer(120f, 20 * min).isEmpty())
        assertTrue(tracker.status.endCandidate)
        assertTrue(tracker.onTick(35 * min).isEmpty())
    }

    @Test
    fun `resumed open stop is closed when vehicle is no longer in P`() {
        tracker.onGear(VehicleGear.GEAR_DRIVE, 10 * min)
        val resumed = ActiveTrip(id = 7L, startedAt = 0L, resumedStop = ResumedStop(startedAt = 5 * min, odometerKm = 120f))
        assertEquals(listOf(CloseStop(7L, 20 * min)), tracker.onActiveTrip(resumed, 20 * min))
    }

    @Test
    fun `resumed stop with gear still unknown closes when gear later shows drive`() {
        val resumed = ActiveTrip(id = 7L, startedAt = 0L, resumedStop = ResumedStop(startedAt = 5 * min, odometerKm = 120f))
        tracker.onActiveTrip(resumed, 20 * min)
        assertEquals(listOf(CloseStop(7L, 21 * min)), tracker.onGear(VehicleGear.GEAR_DRIVE, 21 * min))
    }

    @Test
    fun `thresholds are configurable`() {
        val fast = MotionTracker(idleThresholdMs = 5 * s, endCandidateMs = 10 * s)
        fast.onActiveTrip(trip, 0L)
        fast.onOdometer(1f, 0L)
        fast.onGear(VehicleGear.GEAR_DRIVE, 0L)
        assertEquals(MotionState.IDLE, fast.statusAt(5 * s).motion)
        fast.onGear(VehicleGear.GEAR_PARK, 6 * s)
        assertFalse(fast.statusAt(15 * s).endCandidate)
        assertTrue(fast.statusAt(16 * s).endCandidate)
    }
}
