package com.framemayo.autolog.data.energy

import com.framemayo.autolog.data.local.EnergyEventEntity
import com.framemayo.autolog.data.local.EnergyEventKind
import com.framemayo.autolog.data.local.EnergyLevels
import com.framemayo.autolog.data.local.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyCostEstimatorTest {
    private val estimator = EnergyCostEstimator(EnergyPricing())

    private fun trip(
        startFuel: Float? = null,
        endFuel: Float? = null,
        startBattery: Float? = null,
        endBattery: Float? = null,
        endedAt: Long? = 10_000L,
    ) = TripEntity(
        id = 7,
        driverId = null,
        startedAt = 1_000L,
        endedAt = endedAt,
        startOdometerKm = 0f,
        lastOdometerKm = 10f,
        lastSavedAt = 10_000L,
        startFuelPercent = startFuel,
        endFuelPercent = endFuel,
        startBatteryPercent = startBattery,
        endBatteryPercent = endBattery,
    )

    private fun event(kind: EnergyEventKind, at: Long, before: Float, after: Float, cost: Long? = null, tripId: Long = 7) =
        EnergyEventEntity(
            kind = kind, tripId = tripId, detectedAt = at, odometerKm = null,
            beforePercent = before, afterPercent = after, estimatedAmount = null, estimatedCostWon = cost,
        )

    @Test fun `주유량과 금액 기본 단가`() {
        val e = estimator.refuel(30f, 80f)!!
        assertEquals(25f, e.amount, 1e-4f) // 50% × 50 L
        assertEquals(42_500L, e.costWon) // 25 L × 1,700
    }

    @Test fun `충전은 SDK 용량 우선, 없으면 설정값`() {
        val sdk = estimator.charge(20f, 70f, batteryCapacityKwh = 77.4f)!!
        assertEquals(38.7f, sdk.amount, 1e-3f)
        assertEquals(12_539L, sdk.costWon) // 38.7 × 324 = 12,538.8
        val fallback = estimator.charge(20f, 70f, batteryCapacityKwh = null)!!
        assertEquals(32f, fallback.amount, 1e-4f)
        assertEquals(10_368L, fallback.costWon)
        assertEquals(fallback, estimator.charge(20f, 70f, batteryCapacityKwh = 0f))
    }

    @Test fun `원 단위 반올림`() {
        // 1% × 64 kWh = 0.64 kWh × 324 = 207.36 → 207
        assertEquals(207L, estimator.charge(10f, 11f)!!.costWon)
        // 1% × 50 L = 0.5 L × 1,699 = 849.5 → 850 (사사오입)
        assertEquals(850L, EnergyCostEstimator(EnergyPricing(gasolineWonPerLiter = 1_699.0)).refuel(10f, 11f)!!.costWon)
    }

    @Test fun `상승이 없으면 추정하지 않음`() {
        assertNull(estimator.refuel(50f, 50f))
        assertNull(estimator.charge(50f, 40f))
    }

    @Test fun `운행 소비량과 비용`() {
        val cost = estimator.tripCost(trip(startFuel = 60f, endFuel = 50f), emptyList())!!
        assertEquals(5f, cost.fuelUsedLiters!!, 1e-4f)
        assertEquals(8_500L, cost.fuelCostWon)
        assertNull(cost.electricityUsedKwh)
        assertEquals(8_500L, cost.usageCostWon)
    }

    @Test fun `운행 중 주유는 감지 이벤트만큼 되돌려 소비량 계산`() {
        val refuel = event(EnergyEventKind.REFUEL, at = 5_000L, before = 20f, after = 70f, cost = 42_500L)
        val cost = estimator.tripCost(trip(startFuel = 30f, endFuel = 60f), listOf(refuel))!!
        // 30 − 60 + 50 = 20%p → 10 L → 17,000원
        assertEquals(10f, cost.fuelUsedLiters!!, 1e-4f)
        assertEquals(17_000L, cost.fuelCostWon)
        assertEquals(listOf(refuel), cost.refuels)
        assertEquals(42_500L, cost.refuelCostWon)
    }

    @Test fun `감지 못한 주유로 음수면 소비량은 null, 작은 노이즈는 0`() {
        val unknown = estimator.tripCost(trip(startFuel = 30f, endFuel = 60f), emptyList())
        assertNull(unknown)
        val noisy = estimator.tripCost(trip(startFuel = 50f, endFuel = 51f), emptyList())!!
        assertEquals(0f, noisy.fuelUsedLiters!!, 0f)
        assertEquals(0L, noisy.fuelCostWon)
    }

    @Test fun `운행 시간 밖 이벤트는 비용 목록에만 포함`() {
        val after = event(EnergyEventKind.REFUEL, at = 20_000L, before = 50f, after = 90f, cost = 34_000L)
        val cost = estimator.tripCost(trip(startFuel = 60f, endFuel = 50f), listOf(after))!!
        assertEquals(5f, cost.fuelUsedLiters!!, 1e-4f)
        assertEquals(34_000L, cost.refuelCostWon)
    }

    @Test fun `EV 운행과 운행 중 충전, 진행 중 운행은 현재 잔량 사용`() {
        val charge = event(EnergyEventKind.CHARGE, at = 3_000L, before = 40f, after = 60f, cost = 4_147L)
        val cost = estimator.tripCost(
            trip(startBattery = 80f, endBattery = null, endedAt = null),
            listOf(charge),
            batteryCapacityKwh = null,
            currentLevels = EnergyLevels(batteryPercent = 70f),
        )!!
        // 80 − 70 + 20 = 30%p × 64 = 19.2 kWh × 324 = 6,220.8 → 6,221
        assertEquals(19.2f, cost.electricityUsedKwh!!, 1e-3f)
        assertEquals(6_221L, cost.electricityCostWon)
        assertEquals(4_147L, cost.chargeCostWon)
        assertNull(cost.fuelCostWon)
    }

    @Test fun `잔량도 이벤트도 없으면 null`() {
        assertNull(estimator.tripCost(trip(), emptyList()))
    }
}
