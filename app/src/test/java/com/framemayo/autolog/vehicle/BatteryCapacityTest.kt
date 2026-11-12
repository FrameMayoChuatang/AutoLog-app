package com.framemayo.autolog.vehicle

import com.framemayo.autolog.data.energy.EnergyCostEstimator
import com.framemayo.autolog.data.energy.EnergyPricing
import com.framemayo.autolog.data.local.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryCapacityTest {
    /** 에뮬레이터 SDK가 실제로 준 값(Wh). 가상 콘솔 표시는 77.4 kWh. */
    private val sdkWh = 77_400f

    @Test fun `SDK Wh 값을 kWh로 변환`() = assertEquals(77.4f, sdkBatteryCapacityToKwh(sdkWh)!!, 0.001f)

    @Test fun `이미 kWh로 오는 차량 값은 그대로`() = assertEquals(77.4f, sdkBatteryCapacityToKwh(77.4f)!!, 0.001f)

    @Test fun `없거나 비정상 값은 null로 기본값 사용`() {
        assertNull(sdkBatteryCapacityToKwh(null))
        assertNull(sdkBatteryCapacityToKwh(0f))
        assertNull(sdkBatteryCapacityToKwh(-1f))
        assertNull(sdkBatteryCapacityToKwh(Float.NaN))
        assertNull(sdkBatteryCapacityToKwh(Float.POSITIVE_INFINITY))
        // 5,000 kWh(=5,000,000 Wh)처럼 배터리로 불가능한 값
        assertNull(sdkBatteryCapacityToKwh(5_000_000f))
        assertNull(sdkBatteryCapacityToKwh(0.5f))
    }

    private val estimator = EnergyCostEstimator(EnergyPricing())

    @Test fun `이슈 재현 - 충전 40에서 50퍼센트는 7_74kWh 약 2508원`() {
        val estimate = estimator.charge(40f, 50f, sdkBatteryCapacityToKwh(sdkWh))!!
        assertEquals(7.74f, estimate.amount, 0.001f)
        assertEquals(2_508L, estimate.costWon) // 7.74 kWh × 324원 = 2,507.76원
    }

    @Test fun `운행별 전기 사용량과 비용도 같은 기준`() {
        val trip = TripEntity(
            id = 1, driverId = null, startedAt = 0L, endedAt = 1_000L,
            startOdometerKm = 0f, lastOdometerKm = 10f, lastSavedAt = 1_000L,
            startBatteryPercent = 60f, endBatteryPercent = 50f,
        )
        val cost = estimator.tripCost(trip, emptyList(), sdkBatteryCapacityToKwh(sdkWh))!!
        assertEquals(7.74f, cost.electricityUsedKwh!!, 0.001f)
        assertEquals(2_508L, cost.electricityCostWon)
    }
}
