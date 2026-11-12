package com.framemayo.autolog.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripStatus
import com.framemayo.autolog.vehicle.AutoTripStarter.StartTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTripStarterTest {
    private val starter = AutoTripStarter()

    private fun ended(km: Float, at: Long, status: TripStatus = TripStatus.COMPLETED) = TripEntity(
        id = 1, driverId = 1, startedAt = at - 1_000, endedAt = at, startOdometerKm = km - 5f,
        endOdometerKm = km, lastOdometerKm = km, lastSavedAt = at, status = status,
    )

    @Test fun parkedJitterNeverStartsButMovesBaseline() {
        starter.onGear(VehicleGear.GEAR_PARK, 0)
        assertNull(starter.onOdometer(100f, 10))
        assertNull(starter.onOdometer(100.1f, 20))
        assertNull(starter.onOdometer(100.3f, 30))
        assertEquals(100.3f to 30L, starter.baseline)
    }

    @Test fun startsOnIncreaseInDriveFromBaselineAndParkReleaseTime() {
        starter.onGear(VehicleGear.GEAR_PARK, 0)
        starter.onOdometer(100f, 10)
        starter.onGear(VehicleGear.GEAR_DRIVE, 50)
        assertNull(starter.onOdometer(100f, 60))
        assertEquals(StartTrip(100f, 50), starter.onOdometer(100.1f, 70))
        assertTrue(starter.tripActive)
        assertNull(starter.onOdometer(100.5f, 80))
    }

    @Test fun usesBaselineTimeWhenParkReleaseUnknown() {
        starter.onTripEnded(200f, 1_000)
        starter.onGear(VehicleGear.GEAR_DRIVE, 500) // 운행 중에 이미 D였음
        assertEquals(StartTrip(200f, 1_000), starter.onOdometer(200.2f, 2_000))
    }

    @Test fun epsilonRespected() {
        val s = AutoTripStarter(epsilonKm = 0.2f)
        s.onGear(VehicleGear.GEAR_PARK, 0)
        s.onOdometer(100f, 1)
        s.onGear(VehicleGear.GEAR_DRIVE, 2)
        assertNull(s.onOdometer(100.1f, 3))
        assertNull(s.onOdometer(100.15f, 4))
        assertEquals(StartTrip(100f, 2), s.onOdometer(100.2f, 5))

        val d = AutoTripStarter()
        d.onGear(VehicleGear.GEAR_PARK, 0)
        d.onOdometer(100f, 1)
        d.onGear(VehicleGear.GEAR_DRIVE, 2)
        assertNull(d.onOdometer(100.04f, 3))
        assertEquals(StartTrip(100f, 2), d.onOdometer(100.05f, 4))
    }

    @Test fun noStartWhileActive() {
        starter.onTripActive()
        starter.onGear(VehicleGear.GEAR_DRIVE, 0)
        assertNull(starter.onOdometer(100f, 1))
        assertNull(starter.onOdometer(150f, 2))
    }

    @Test fun manualEndDoesNotImmediatelyRestart() {
        starter.onTripActive()
        starter.onGear(VehicleGear.GEAR_DRIVE, 0)
        starter.onTripEnded(ended(300f, 1_000))
        assertNull(starter.onOdometer(300f, 1_100))
        assertNull(starter.onOdometer(300.02f, 1_200))
        assertEquals(StartTrip(300f, 1_000), starter.onOdometer(300.1f, 1_300))
    }

    @Test fun manualEndThenParkedDoesNotRestart() {
        starter.onTripActive()
        starter.onTripEnded(ended(300f, 1_000))
        starter.onGear(VehicleGear.GEAR_PARK, 1_050)
        assertNull(starter.onOdometer(300.4f, 1_100))
        assertEquals(300.4f to 1_100L, starter.baseline)
    }

    @Test fun seedsFromLastCompletedTrip() {
        starter.seed(ended(500f, 9_000))
        starter.onGear(VehicleGear.GEAR_DRIVE, 10_000)
        assertEquals(StartTrip(500f, 10_000), starter.onOdometer(500.3f, 10_500))
    }

    @Test fun seedFromParkedReadingKeepsLiveBaseline() {
        starter.onGear(VehicleGear.GEAR_PARK, 0)
        starter.onOdometer(510f, 100)
        starter.seed(ended(500f, 50))
        assertEquals(510f to 100L, starter.baseline)
    }

    @Test fun seedIgnoresInterruptedTrip() {
        starter.seed(ended(500f, 9_000, TripStatus.INTERRUPTED))
        assertEquals(null, starter.baseline)
        starter.onGear(VehicleGear.GEAR_DRIVE, 10_000)
        assertNull(starter.onOdometer(520f, 10_100))
        assertEquals(StartTrip(520f, 10_100), starter.onOdometer(520.1f, 10_200))
    }

    @Test fun seedWithActiveTripBlocksStart() {
        starter.seed(ended(500f, 9_000), activeTrip = ended(500f, 9_500, TripStatus.ACTIVE))
        starter.onGear(VehicleGear.GEAR_DRIVE, 10_000)
        assertNull(starter.onOdometer(510f, 10_100))
    }

    @Test fun staleSeedFarBehindFirstReadingIsDiscarded() {
        starter.seed(ended(500f, 9_000))
        starter.onGear(VehicleGear.GEAR_DRIVE, 10_000)
        assertNull(starter.onOdometer(560f, 10_100))
        assertEquals(560f to 10_100L, starter.baseline)
        assertEquals(StartTrip(560f, 10_100), starter.onOdometer(560.2f, 10_200))
    }

    @Test fun nullGearStartsOnOdometerIncreaseOnly() {
        starter.onGear(null, 0)
        assertNull(starter.onOdometer(100f, 10))
        starter.onGear(null, 20)
        assertNull(starter.onOdometer(100f, 30))
        assertEquals(StartTrip(100f, 10), starter.onOdometer(100.1f, 40))
    }

    @Test fun nullAfterParkKeepsParkUnknownUntilKnownRelease() {
        starter.onGear(VehicleGear.GEAR_PARK, 0)
        starter.onOdometer(100f, 10)
        starter.onGear(null, 20)
        starter.onGear(VehicleGear.GEAR_DRIVE, 30)
        assertEquals(StartTrip(100f, 30), starter.onOdometer(100.1f, 40))
    }

    @Test fun failedStartRetriesFromSameBaseline() {
        starter.onTripEnded(100f, 0)
        starter.onGear(VehicleGear.GEAR_DRIVE, 0)
        assertEquals(StartTrip(100f, 0), starter.onOdometer(100.1f, 10))
        starter.onStartFailed()
        assertEquals(StartTrip(100f, 0), starter.onOdometer(100.2f, 20))
    }

    @Test fun interruptedEndResetsBaseline() {
        starter.onTripActive()
        starter.onTripEnded(ended(100f, 0, TripStatus.INTERRUPTED))
        starter.onGear(VehicleGear.GEAR_DRIVE, 0)
        assertNull(starter.onOdometer(120f, 10))
        assertEquals(120f to 10L, starter.baseline)
    }
}
