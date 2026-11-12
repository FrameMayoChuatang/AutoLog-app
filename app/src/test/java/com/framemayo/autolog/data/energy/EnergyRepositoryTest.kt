package com.framemayo.autolog.data.energy

import com.framemayo.autolog.data.local.EnergyEventDao
import com.framemayo.autolog.data.local.EnergyEventEntity
import com.framemayo.autolog.data.local.EnergyEventKind
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.vehicle.DetectedEnergyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeEnergyEventDao : EnergyEventDao {
    val rows = MutableStateFlow<List<EnergyEventEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(event: EnergyEventEntity): Long {
        val id = nextId++
        rows.value = rows.value + event.copy(id = id)
        return id
    }

    override fun observeRecent(limit: Int): Flow<List<EnergyEventEntity>> =
        rows.map { list -> list.sortedByDescending { it.detectedAt }.take(limit) }

    override suspend fun forTrip(tripId: Long): List<EnergyEventEntity> =
        rows.value.filter { it.tripId == tripId }.sortedBy { it.detectedAt }
}

class EnergyRepositoryTest {
    @Test fun `주유 기록 시 추정 양과 금액을 채운다`() = runBlocking {
        val dao = FakeEnergyEventDao()
        val repo = EnergyRepository(dao)
        val saved = repo.record(DetectedEnergyEvent(EnergyEventKind.REFUEL, 5_000L, 1234f, 30f, 80f), tripId = 7, batteryCapacityKwh = null)
        assertEquals(1L, saved.id)
        assertEquals(7L, saved.tripId)
        assertEquals(25f, saved.estimatedAmount!!, 1e-4f)
        assertEquals(42_500L, saved.estimatedCostWon)
        assertEquals(1234f, saved.odometerKm)
        assertEquals(listOf(saved), repo.recent.first())
    }

    @Test fun `충전 기록은 SDK 용량을 쓴다`() = runBlocking {
        val repo = EnergyRepository(FakeEnergyEventDao())
        val saved = repo.record(DetectedEnergyEvent(EnergyEventKind.CHARGE, 1L, null, 50f, 60f), tripId = null, batteryCapacityKwh = 80f)
        assertEquals(8f, saved.estimatedAmount!!, 1e-4f)
        assertEquals(2_592L, saved.estimatedCostWon)
        assertNull(saved.tripId)
    }

    @Test fun `최근 목록은 최신순·개수 제한`() = runBlocking {
        val repo = EnergyRepository(FakeEnergyEventDao(), recentLimit = 2)
        listOf(1L, 3L, 2L).forEach {
            repo.record(DetectedEnergyEvent(EnergyEventKind.REFUEL, it, null, 10f, 20f), tripId = null)
        }
        assertEquals(listOf(3L, 2L), repo.recent.first().map { it.detectedAt })
    }

    @Test fun `운행 비용은 그 운행 이벤트로 계산`() = runBlocking {
        val repo = EnergyRepository(FakeEnergyEventDao())
        repo.record(DetectedEnergyEvent(EnergyEventKind.REFUEL, 5_000L, null, 20f, 70f), tripId = 7)
        repo.record(DetectedEnergyEvent(EnergyEventKind.REFUEL, 6_000L, null, 10f, 90f), tripId = 8)
        val trip = TripEntity(
            id = 7, driverId = null, startedAt = 1_000L, endedAt = 10_000L,
            startOdometerKm = 0f, lastOdometerKm = 10f, lastSavedAt = 10_000L,
            startFuelPercent = 30f, endFuelPercent = 60f,
        )
        val cost = repo.tripCost(trip)!!
        assertEquals(17_000L, cost.fuelCostWon)
        assertEquals(42_500L, cost.refuelCostWon)
        assertEquals(1, repo.forTrip(7).size)
    }
}
