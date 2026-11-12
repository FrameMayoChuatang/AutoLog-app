package com.framemayo.autolog.data.repository

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.framemayo.autolog.data.local.TripStopDao
import com.framemayo.autolog.data.local.TripStopEntity
import com.framemayo.autolog.vehicle.ActiveTrip
import com.framemayo.autolog.vehicle.MotionAction
import com.framemayo.autolog.vehicle.MotionTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeTripStopDao : TripStopDao {
    val rows = MutableStateFlow<List<TripStopEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(stop: TripStopEntity): Long {
        val id = nextId++
        rows.value = rows.value + stop.copy(id = id)
        return id
    }

    override suspend fun open(tripId: Long): TripStopEntity? =
        rows.value.filter { it.tripId == tripId && it.endedAt == null }.maxByOrNull { it.id }

    override suspend fun close(tripId: Long, endedAt: Long) {
        rows.value = rows.value.map { if (it.tripId == tripId && it.endedAt == null) it.copy(endedAt = endedAt) else it }
    }

    override suspend fun forTrip(tripId: Long): List<TripStopEntity> =
        rows.value.filter { it.tripId == tripId }.sortedBy { it.startedAt }

    override fun observeForTrip(tripId: Long): Flow<List<TripStopEntity>> =
        rows.map { list -> list.filter { it.tripId == tripId }.sortedBy { it.startedAt } }
}

class TripStopRepositoryTest {
    private val dao = FakeTripStopDao()
    private val repo = TripStopRepository(dao)

    @Test
    fun `open is idempotent while a stop is open`() = runBlocking {
        val first = repo.open(1L, 100L, 10f)
        val second = repo.open(1L, 200L, 11f)
        assertEquals(first, second)
        assertEquals(1, dao.forTrip(1L).size)
    }

    @Test
    fun `close without open stop is a no-op and close clamps to start`() = runBlocking {
        repo.close(1L, 50L)
        assertEquals(0, dao.rows.value.size)
        repo.open(1L, 100L, 10f)
        repo.close(1L, 90L)
        repo.close(1L, 300L)
        assertEquals(listOf(TripStopEntity(1L, 1L, 100L, 100L, 10f)), dao.forTrip(1L))
        assertNull(repo.openStop(1L))
    }

    @Test
    fun `applying tracker actions records each parked interval of a trip`() = runBlocking {
        val tracker = MotionTracker()
        val actions = mutableListOf<MotionAction>()
        actions += tracker.onActiveTrip(ActiveTrip(5L, 0L), 0L)
        actions += tracker.onOdometer(10f, 1_000L)
        actions += tracker.onGear(VehicleGear.GEAR_DRIVE, 1_000L)
        actions += tracker.onGear(VehicleGear.GEAR_PARK, 60_000L)
        actions += tracker.onGear(VehicleGear.GEAR_DRIVE, 120_000L)
        actions += tracker.onOdometer(12f, 300_000L)
        actions += tracker.onGear(VehicleGear.GEAR_PARK, 400_000L)
        actions += tracker.onTick(400_000L + 30 * 60_000L)
        actions += tracker.onActiveTrip(null, 2_500_000L)
        actions.forEach { repo.apply(it) }
        // 같은 동작을 다시 적용해도 행이 늘지 않는다.
        actions.forEach { repo.apply(it) }

        assertEquals(
            listOf(
                TripStopEntity(1L, 5L, 60_000L, 120_000L, 10f),
                TripStopEntity(2L, 5L, 400_000L, 2_500_000L, 12f),
            ),
            dao.forTrip(5L),
        )
    }
}
