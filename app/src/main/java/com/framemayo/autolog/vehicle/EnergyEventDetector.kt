package com.framemayo.autolog.vehicle

import com.framemayo.autolog.data.local.EnergyEventKind

/**
 * 감지한 주유·충전 한 건.
 *
 * [detectedAt]은 이벤트를 내보낸 시각이 아니라 잔량이 마지막으로 오른 시각(주유·충전이 끝난 시점)이다.
 * 차량이 움직이기 시작할 때 확정되어 새 운행이 이미 시작됐더라도, 운행 시작 전에 끝난 주유임을 시각으로 구분할 수 있다.
 */
data class DetectedEnergyEvent(
    val kind: EnergyEventKind,
    val detectedAt: Long,
    val odometerKm: Float?,
    val before: Float,
    val after: Float,
)

/**
 * 연료량·EV 배터리 잔량·충전구 상태로 주유·충전을 감지하는 결정적 상태 기계. 안드로이드 타입에 의존하지 않는다.
 *
 * 모든 `on*` 메서드는 이번 입력으로 확정된 이벤트를 돌려준다(대부분 빈 목록). 호출자는 정차 여부를
 * [onMotion]으로, 주기 타이머를 [onTick]으로 알려야 한다. 측정값이 한 번도 오지 않으면(미지원 차량) 아무것도 내보내지 않는다.
 *
 * **주유(REFUEL)** — 정차 중에만 본다.
 * - 기준값: 정차가 시작될 때의 연료량(그 시점 값이 없으면 정차 중 첫 측정값).
 * - 현재값이 기준값보다 [refuelThresholdPercent] 이상 높고, 연료량이 [refuelSettleMs] 동안 더 이상 오르지 않으면(새 최고치 없음) 확정한다.
 *   정착 전에 차가 움직이기 시작하면 그 순간 같은 조건(현재값 ≥ 기준+임계)으로 확정한다.
 * - 확정 시 after = 그 시점의 최신 측정값(노이즈 최고치보다 편향이 적다).
 * - 출렁임(±2%p 수준)은 임계 5%p에 닿지 않고, 일시적으로 튄 값도 다시 내려오면 확정 시점의 현재값 조건에서 걸러진다.
 * - 정차 한 번에 최대 한 건. 주행 중 상승은 무시한다.
 *
 * **충전(CHARGE)** — 세션 단위로 한 건, 세션이 끝날 때 확정한다.
 * - 시작: 충전구가 열림(정차 중이거나 정차 여부 미상일 때) 또는 정차 중 배터리가 정차 중 최저값보다 [chargeThresholdPercent] 이상 오름.
 * - 종료: 충전구 닫힘, 차량 움직임, 또는 충전구가 열려 있다고 알려지지 않은 상태에서 [chargeIdleMs] 동안 상승 없음.
 *   충전구가 열려 있는 동안에는 완속 충전(%당 10분 이상)도 끊지 않도록 무상승 타임아웃을 쓰지 않는다.
 * - before = 세션 시작 기준값, after = 세션 중 최고값(BMS SoC는 매끄러워 최고값이 실제 종료 잔량에 가깝다).
 * - 충전구로 시작한 세션은 [chargePortMinRisePercent] 이상, 그 외는 [chargeThresholdPercent] 이상 올라야 기록한다.
 *
 * 스레드 안전을 위해 메서드는 동기화되어 있다.
 */
class EnergyEventDetector(
    private val refuelThresholdPercent: Float = 5f,
    private val refuelSettleMs: Long = 60_000L,
    private val chargeThresholdPercent: Float = 3f,
    private val chargePortMinRisePercent: Float = 1f,
    private val chargeIdleMs: Long = 30 * 60_000L,
) {
    private var stationary: Boolean? = null
    private var odometerKm: Float? = null

    // 주유
    private var fuel: Float? = null
    private var fuelBaseline: Float? = null
    private var fuelPeak: Float? = null
    private var fuelLastRiseAt: Long = 0L
    private var refuelDone = false

    // 충전
    private var battery: Float? = null
    private var batteryLow: Float? = null
    private var portOpen: Boolean? = null
    private var session: ChargeSession? = null

    private class ChargeSession(
        val byPort: Boolean,
        var before: Float?,
        var peak: Float?,
        var lastRiseAt: Long,
    )

    @Synchronized
    fun onMotion(stationary: Boolean, now: Long, odometerKm: Float? = null): List<DetectedEnergyEvent> {
        odometerKm?.let { this.odometerKm = it }
        val previous = this.stationary
        this.stationary = stationary
        if (stationary) {
            if (previous != true) beginStop(now)
            return emptyList()
        }
        val events = buildList {
            if (previous == true) checkRefuel(now, moving = true)?.let(::add)
            finishCharge()?.let(::add)
        }
        fuelBaseline = null
        fuelPeak = null
        batteryLow = null
        return events
    }

    @Synchronized
    fun onFuel(percent: Float?, now: Long): List<DetectedEnergyEvent> {
        val p = percent?.takeIf(::isValidPercent) ?: return emptyList()
        fuel = p
        if (stationary != true) return emptyList()
        if (fuelBaseline == null) {
            fuelBaseline = p
            fuelPeak = p
            fuelLastRiseAt = now
            return emptyList()
        }
        if (refuelDone) return emptyList()
        if (p > (fuelPeak ?: p)) {
            fuelPeak = p
            fuelLastRiseAt = now
        }
        return listOfNotNull(checkRefuel(now, moving = false))
    }

    @Synchronized
    fun onBattery(percent: Float?, now: Long): List<DetectedEnergyEvent> {
        val p = percent?.takeIf(::isValidPercent) ?: return emptyList()
        battery = p
        session?.let { s ->
            val peak = s.peak
            if (s.before == null || peak == null) {
                s.before = p
                s.peak = p
                s.lastRiseAt = now
            } else if (p > peak) {
                s.peak = p
                s.lastRiseAt = now
            }
            return emptyList()
        }
        if (stationary != true) return emptyList()
        val low = batteryLow
        if (low == null || p < low) {
            batteryLow = p
            return emptyList()
        }
        if (p - low >= chargeThresholdPercent) {
            session = ChargeSession(byPort = false, before = low, peak = p, lastRiseAt = now)
        }
        return emptyList()
    }

    @Synchronized
    fun onChargePort(open: Boolean?, now: Long): List<DetectedEnergyEvent> {
        val previous = portOpen
        portOpen = open
        if (open == true && previous != true) {
            if (stationary == false) return emptyList()
            val current = session
            if (current == null) {
                val base = listOfNotNull(battery, batteryLow).minOrNull()
                session = ChargeSession(byPort = true, before = base, peak = battery ?: base, lastRiseAt = now)
            } else if (!current.byPort) {
                session = ChargeSession(true, current.before, current.peak, current.lastRiseAt)
            }
            return emptyList()
        }
        if (open == false && previous == true) return listOfNotNull(finishCharge())
        return emptyList()
    }

    @Synchronized
    fun onTick(now: Long): List<DetectedEnergyEvent> = buildList {
        if (stationary == true) checkRefuel(now, moving = false)?.let(::add)
        val s = session
        if (s != null && portOpen != true && now - s.lastRiseAt >= chargeIdleMs) finishCharge()?.let(::add)
    }

    private fun beginStop(now: Long) {
        fuelBaseline = fuel
        fuelPeak = fuel
        fuelLastRiseAt = now
        refuelDone = false
        batteryLow = battery
    }

    private fun checkRefuel(now: Long, moving: Boolean): DetectedEnergyEvent? {
        if (refuelDone) return null
        val base = fuelBaseline ?: return null
        val current = fuel ?: return null
        if (current - base < refuelThresholdPercent) return null
        if (!moving && now - fuelLastRiseAt < refuelSettleMs) return null
        refuelDone = true
        return DetectedEnergyEvent(EnergyEventKind.REFUEL, fuelLastRiseAt, odometerKm, base, current)
    }

    private fun finishCharge(): DetectedEnergyEvent? {
        val s = session ?: return null
        session = null
        // 같은 정차에서 다시 충전하려면 이번 세션 이후 잔량에서 새로 올라야 한다.
        batteryLow = battery
        val before = s.before ?: return null
        val after = s.peak ?: return null
        val minRise = if (s.byPort) chargePortMinRisePercent else chargeThresholdPercent
        if (after - before < minRise) return null
        return DetectedEnergyEvent(EnergyEventKind.CHARGE, s.lastRiseAt, odometerKm, before, after)
    }

    private fun isValidPercent(value: Float): Boolean = value.isFinite() && value in 0f..100f
}
