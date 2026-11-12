package com.framemayo.autolog.service

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.framemayo.autolog.data.fakeTripDao
import com.framemayo.autolog.data.local.EnergyLevels
import com.framemayo.autolog.data.local.TripStatus
import com.framemayo.autolog.data.local.TripTrigger
import com.framemayo.autolog.data.repository.TripRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTripControllerTest {
    private var now = 1_000_000L
    private val repo = TripRepositoryImpl(fakeTripDao(), clock = { now })
    private val controller = AutoTripController(repo, energy = { EnergyLevels(fuelPercent = 40f, batteryPercent = 80f) })

    @Test fun startsAutoTripWithoutDriverFromBaseline() = runBlocking {
        controller.seed()
        controller.onGear(VehicleGear.GEAR_PARK, 100)
        assertNull(controller.onOdometer(1_000f, 200))
        controller.onGear(VehicleGear.GEAR_DRIVE, 300)
        val trip = controller.onOdometer(1_000.1f, 400)
        assertNotNull(trip)
        trip!!
        assertNull(trip.driverId)
        assertEquals(TripTrigger.AUTO, trip.startedBy)
        assertEquals(1_000f, trip.startOdometerKm)
        assertEquals(300L, trip.startedAt)
        assertEquals(40f, trip.startFuelPercent)
        assertEquals(80f, trip.startBatteryPercent)
        assertNull(controller.onOdometer(1_000.5f, 500))
    }

    @Test fun parkedJitterNeverStarts() = runBlocking {
        controller.seed()
        controller.onGear(VehicleGear.GEAR_PARK, 0)
        for (i in 0..20) assertNull(controller.onOdometer(1_000f + i * 0.1f, i * 10L))
        assertNull(repo.lastEnded())
    }

    @Test fun seedsFromLastEndedAndDoesNotRestartAfterManualEnd() = runBlocking {
        val manual = repo.start(7, 900f, startedAt = 10)
        controller.onActiveTrip(manual)
        controller.onGear(VehicleGear.GEAR_DRIVE, 20)
        assertNull(controller.onOdometer(950f, 30))
        now = 2_000_000L
        val ended = repo.end(980f)!!
        controller.onActiveTrip(null)
        assertNull(controller.onOdometer(980f, 2_000_100))
        assertNull(controller.onOdometer(980.03f, 2_000_200))
        val auto = controller.onOdometer(980.2f, 2_000_300)!!
        assertEquals(980f, auto.startOdometerKm)
        assertEquals(ended.endedAt, auto.startedAt)

        // 새 서비스 인스턴스는 lastEnded로 기준점을 잡는다.
        repo.end(990f)
        val fresh = AutoTripController(repo)
        fresh.seed()
        fresh.onGear(VehicleGear.GEAR_DRIVE, 3_000_000)
        val next = fresh.onOdometer(990.1f, 3_000_100)!!
        assertEquals(990f, next.startOdometerKm)
        assertEquals(3_000_000L, next.startedAt)
    }

    @Test fun staleNullEmissionWhileTripActiveIsIgnored() = runBlocking {
        controller.seed()
        controller.onGear(VehicleGear.GEAR_DRIVE, 0)
        controller.onOdometer(100f, 10)
        val trip = controller.onOdometer(100.1f, 20)!!
        controller.onActiveTrip(null) // 생성 전 계산된 오래된 방출
        assertNull(controller.onOdometer(101f, 30))
        assertEquals(TripStatus.ACTIVE, repo.get(trip.id)!!.status)
    }

    @Test fun manualTripStartedConcurrentlyIsNotReportedAsAuto() = runBlocking {
        controller.seed()
        controller.onGear(VehicleGear.GEAR_DRIVE, 0)
        controller.onOdometer(100f, 10)
        repo.start(3, 100f) // 수동 시작했지만 active 방출이 아직 도착하지 않음
        assertNull(controller.onOdometer(100.1f, 20))
        assertNull(controller.onOdometer(100.5f, 30))
    }

    @Test fun concurrentOdometerStartsExactlyOneTrip() = runBlocking {
        var starts = 0
        val counting = object : com.framemayo.autolog.data.repository.TripRepository by repo {
            override suspend fun start(
                driverId: Long?, odometerKm: Float, startedAt: Long?, trigger: TripTrigger, energy: EnergyLevels?,
            ) = run { starts++; kotlinx.coroutines.delay(5); repo.start(driverId, odometerKm, startedAt, trigger, energy) }
        }
        val c = AutoTripController(counting)
        c.seed()
        c.onGear(VehicleGear.GEAR_DRIVE, 0)
        c.onOdometer(100f, 1)
        val started = (1..50).map { i -> async(Dispatchers.Default) { c.onOdometer(100f + i * 0.1f, 10L + i) } }.awaitAll()
        assertEquals(1, started.count { it != null })
        assertEquals(1, starts)
    }

    @Test fun failedStartIsReportedAndRetried() = runBlocking {
        var fail = true
        val errors = mutableListOf<Throwable>()
        val flaky = object : com.framemayo.autolog.data.repository.TripRepository by repo {
            override suspend fun start(
                driverId: Long?, odometerKm: Float, startedAt: Long?, trigger: TripTrigger, energy: EnergyLevels?,
            ) = if (fail) error("db") else repo.start(driverId, odometerKm, startedAt, trigger, energy)
        }
        val c = AutoTripController(flaky, onError = { errors += it })
        c.seed()
        c.onGear(VehicleGear.GEAR_DRIVE, 0)
        c.onOdometer(100f, 1)
        assertNull(c.onOdometer(100.1f, 2))
        assertEquals(1, errors.size)
        fail = false
        assertEquals(100f, c.onOdometer(100.2f, 3)!!.startOdometerKm)
    }
}
