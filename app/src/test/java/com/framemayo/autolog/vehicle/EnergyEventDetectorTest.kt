package com.framemayo.autolog.vehicle

import com.framemayo.autolog.data.local.EnergyEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyEventDetectorTest {
    private val sec = 1_000L
    private val min = 60_000L

    private fun detector() = EnergyEventDetector(
        refuelThresholdPercent = 5f,
        refuelSettleMs = 60 * sec,
        chargeThresholdPercent = 3f,
        chargePortMinRisePercent = 1f,
        chargeIdleMs = 30 * min,
    )

    @Test fun `정차 중 주유는 상승이 멈춘 뒤 한 번만 감지`() {
        val d = detector()
        val out = mutableListOf<DetectedEnergyEvent>()
        out += d.onFuel(30f, 0)
        out += d.onMotion(stationary = true, now = 10 * sec, odometerKm = 1234f)
        var t = 20 * sec
        for (p in 31..80) { out += d.onFuel(p.toFloat(), t); t += 2 * sec }
        val lastRise = t - 2 * sec
        out += d.onTick(lastRise + 30 * sec)
        assertTrue(out.isEmpty())
        out += d.onTick(lastRise + 60 * sec)
        assertEquals(1, out.size)
        val e = out.single()
        assertEquals(EnergyEventKind.REFUEL, e.kind)
        assertEquals(30f, e.before)
        assertEquals(80f, e.after)
        assertEquals(lastRise, e.detectedAt)
        assertEquals(1234f, e.odometerKm)

        // 같은 정차에서 추가 상승·이동이 있어도 다시 내지 않는다.
        assertTrue(d.onFuel(86f, lastRise + 70 * sec).isEmpty())
        assertTrue(d.onTick(lastRise + 10 * min).isEmpty())
        assertTrue(d.onMotion(false, lastRise + 11 * min, 1234f).isEmpty())
    }

    @Test fun `정착 전에 출발하면 출발 시점에 확정`() {
        val d = detector()
        d.onFuel(20f, 0)
        d.onMotion(true, 0, 10f)
        d.onFuel(50f, 10 * sec)
        val e = d.onMotion(false, 20 * sec, 10f).single()
        assertEquals(20f, e.before)
        assertEquals(50f, e.after)
        assertEquals(10 * sec, e.detectedAt)
    }

    @Test fun `정차 시작 전 값이 없으면 정차 중 첫 측정값이 기준`() {
        val d = detector()
        d.onMotion(true, 0)
        d.onFuel(40f, 1 * sec)
        d.onFuel(60f, 2 * sec)
        val e = d.onTick(2 * sec + min).single()
        assertEquals(40f, e.before)
        assertEquals(60f, e.after)
    }

    @Test fun `연료 출렁임(±2퍼센트)은 감지하지 않음`() {
        val d = detector()
        d.onFuel(40f, 0)
        d.onMotion(true, 0)
        val noise = listOf(42f, 38f, 41f, 39f, 42f, 40f, 38f, 42f)
        var t = 0L
        repeat(20) { i ->
            t += 30 * sec
            assertTrue(d.onFuel(noise[i % noise.size], t).isEmpty())
            assertTrue(d.onTick(t).isEmpty())
        }
        assertTrue(d.onTick(t + 10 * min).isEmpty())
        assertTrue(d.onMotion(false, t + 11 * min).isEmpty())
    }

    @Test fun `잠깐 튄 값이 다시 내려오면 감지하지 않음`() {
        val d = detector()
        d.onFuel(40f, 0)
        d.onMotion(true, 0)
        d.onFuel(46f, 10 * sec)
        d.onFuel(40f, 12 * sec)
        assertTrue(d.onTick(5 * min).isEmpty())
        assertTrue(d.onMotion(false, 6 * min).isEmpty())
    }

    @Test fun `주행 중 상승은 무시`() {
        val d = detector()
        d.onMotion(false, 0)
        d.onFuel(30f, 1 * sec)
        assertTrue(d.onFuel(60f, 2 * sec).isEmpty())
        assertTrue(d.onTick(10 * min).isEmpty())
        // 정차 시작 시점 값(60)이 기준이 되어 그대로면 이벤트 없음.
        assertTrue(d.onMotion(true, 11 * min).isEmpty())
        assertTrue(d.onTick(20 * min).isEmpty())
    }

    @Test fun `정차마다 새로 판단`() {
        val d = detector()
        d.onFuel(20f, 0)
        d.onMotion(true, 0)
        d.onFuel(50f, sec)
        assertEquals(1, d.onTick(2 * min).size)
        d.onMotion(false, 3 * min)
        d.onFuel(45f, 30 * min)
        d.onMotion(true, 31 * min)
        d.onFuel(90f, 32 * min)
        val e = d.onTick(34 * min).single()
        assertEquals(45f, e.before)
        assertEquals(90f, e.after)
    }

    @Test fun `정차 중 배터리 상승 충전 세션은 무상승 후 확정`() {
        val d = detector()
        d.onBattery(50f, 0)
        d.onMotion(true, 0, 500f)
        d.onBattery(49f, 1 * min) // 정차 중 소모 → 최저값이 기준
        var t = 2 * min
        for (p in 50..80) { assertTrue(d.onBattery(p.toFloat(), t).isEmpty()); t += min }
        val lastRise = t - min
        assertTrue(d.onTick(lastRise + 29 * min).isEmpty())
        val e = d.onTick(lastRise + 30 * min).single()
        assertEquals(EnergyEventKind.CHARGE, e.kind)
        assertEquals(49f, e.before)
        assertEquals(80f, e.after)
        assertEquals(lastRise, e.detectedAt)
        assertEquals(500f, e.odometerKm)
        assertTrue(d.onTick(lastRise + 60 * min).isEmpty())
    }

    @Test fun `충전 세션은 출발하면 확정`() {
        val d = detector()
        d.onBattery(30f, 0)
        d.onMotion(true, 0)
        d.onBattery(35f, min)
        d.onBattery(40f, 2 * min)
        val e = d.onMotion(false, 3 * min).single()
        assertEquals(30f, e.before)
        assertEquals(40f, e.after)
    }

    @Test fun `충전구 열림부터 닫힘까지 한 세션, 열린 동안은 완속도 끊지 않음`() {
        val d = detector()
        d.onBattery(20f, 0)
        d.onMotion(true, 0)
        assertTrue(d.onChargePort(true, min).isEmpty())
        d.onBattery(21f, 15 * min)
        d.onBattery(22f, 60 * min)
        assertTrue(d.onTick(120 * min).isEmpty()) // 충전구 열림 → 무상승 타임아웃 없음
        d.onBattery(23f, 130 * min)
        val e = d.onChargePort(false, 140 * min).single()
        assertEquals(20f, e.before)
        assertEquals(23f, e.after)
        assertEquals(130 * min, e.detectedAt)
        assertTrue(d.onChargePort(false, 141 * min).isEmpty())
        assertTrue(d.onMotion(false, 142 * min).isEmpty())
    }

    @Test fun `충전구만 열었다 닫으면 기록하지 않음`() {
        val d = detector()
        d.onBattery(60f, 0)
        d.onMotion(true, 0)
        d.onChargePort(true, min)
        assertTrue(d.onChargePort(false, 2 * min).isEmpty())
    }

    @Test fun `충전구 열림 후 첫 배터리 값이 기준`() {
        val d = detector()
        d.onMotion(true, 0)
        d.onChargePort(true, 0)
        d.onBattery(10f, min)
        d.onBattery(12f, 5 * min)
        val e = d.onChargePort(false, 6 * min).single()
        assertEquals(10f, e.before)
        assertEquals(12f, e.after)
    }

    @Test fun `주행 중 충전구 신호는 무시`() {
        val d = detector()
        d.onBattery(50f, 0)
        d.onMotion(false, 0)
        assertTrue(d.onChargePort(true, min).isEmpty())
        d.onBattery(55f, 2 * min)
        assertTrue(d.onChargePort(false, 3 * min).isEmpty())
    }

    @Test fun `측정값이 없으면 아무것도 내지 않음`() {
        val d = detector()
        val out = mutableListOf<DetectedEnergyEvent>()
        out += d.onMotion(true, 0)
        out += d.onFuel(null, sec)
        out += d.onBattery(null, sec)
        out += d.onFuel(Float.NaN, sec)
        out += d.onChargePort(null, sec)
        out += d.onTick(2 * min)
        out += d.onMotion(false, 3 * min)
        out += d.onTick(60 * min)
        assertTrue(out.isEmpty())
    }
}
