package com.framemayo.autolog.data.energy

import com.framemayo.autolog.data.local.EnergyEventEntity
import com.framemayo.autolog.data.local.EnergyEventKind
import com.framemayo.autolog.data.local.EnergyLevels
import com.framemayo.autolog.data.local.TripEntity
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 비용 추정에 쓰는 단가·용량. **모두 추정용 기본값**이며 실제 결제 금액이 아니다.
 * 탱크 용량은 SDK가 제공하지 않으므로 설정값을 쓰고, 배터리 용량은 SDK 값이 있으면 그것을 우선한다.
 */
data class EnergyPricing(
    /** 휘발유 단가(원/L). 추정치. */
    val gasolineWonPerLiter: Double = 1_700.0,
    /** 연료 탱크 용량(L). 추정치. */
    val fuelTankLiters: Double = 50.0,
    /** 전기 충전 단가(원/kWh). 추정치. */
    val electricityWonPerKwh: Double = 324.0,
    /** SDK에서 용량을 얻지 못할 때 쓰는 배터리 용량(kWh). 추정치. */
    val batteryCapacityKwh: Double = 64.0,
)

/** 추정 양(연료는 L, 전기는 kWh)과 금액(원, 반올림). */
data class EnergyEstimate(val amount: Float, val costWon: Long)

/**
 * 운행 한 건의 에너지 사용·보충 추정.
 * - fuel/electricity Used: 운행 중 소비량과 그 비용(시작·종료 잔량을 모르거나 계산이 불가능하면 null).
 * - refuels/charges: 이 운행에 연결된 주유·충전 이벤트(저장 시 계산한 추정 금액 포함).
 */
data class TripEnergyCost(
    val fuelUsedLiters: Float?,
    val fuelCostWon: Long?,
    val electricityUsedKwh: Float?,
    val electricityCostWon: Long?,
    val refuels: List<EnergyEventEntity>,
    val charges: List<EnergyEventEntity>,
) {
    /** 운행 중 소비한 에너지의 추정 비용 합. 둘 다 모르면 null. */
    val usageCostWon: Long?
        get() = if (fuelCostWon == null && electricityCostWon == null) null
        else (fuelCostWon ?: 0L) + (electricityCostWon ?: 0L)

    val refuelCostWon: Long get() = refuels.sumOf { it.estimatedCostWon ?: 0L }
    val chargeCostWon: Long get() = charges.sumOf { it.estimatedCostWon ?: 0L }
}

class EnergyCostEstimator(
    val pricing: EnergyPricing = EnergyPricing(),
    /** 운행 소비량이 이 값(%p)까지 음수인 것은 센서 노이즈로 보고 0으로 처리한다. 더 크면 계산 불가(null). */
    private val consumptionNoisePercent: Float = 2f,
) {
    /** 주유량 L = (after−before)/100 × 탱크 용량, 금액 = L × 단가. 상승이 없으면 null. */
    fun refuel(beforePercent: Float, afterPercent: Float): EnergyEstimate? {
        val delta = afterPercent - beforePercent
        if (!(delta > 0f)) return null
        return fuel(delta)
    }

    /** 충전량 kWh = (after−before)/100 × 배터리 용량(SDK 값 우선, 없으면 설정값), 금액 = kWh × 단가. */
    fun charge(beforePercent: Float, afterPercent: Float, batteryCapacityKwh: Float? = null): EnergyEstimate? {
        val delta = afterPercent - beforePercent
        if (!(delta > 0f)) return null
        return electricity(delta, batteryCapacityKwh)
    }

    fun estimate(
        kind: EnergyEventKind,
        beforePercent: Float,
        afterPercent: Float,
        batteryCapacityKwh: Float? = null,
    ): EnergyEstimate? = when (kind) {
        EnergyEventKind.REFUEL -> refuel(beforePercent, afterPercent)
        EnergyEventKind.CHARGE -> charge(beforePercent, afterPercent, batteryCapacityKwh)
    }

    /**
     * 운행 중 소비량과 비용.
     *
     * 소비 %p = 시작 − 종료 + (운행 시간 [startedAt, endedAt] 안에 감지된 같은 종류 보충 이벤트의 상승분 합).
     * 운행 중 주유·충전으로 종료 잔량이 더 높아도 감지된 이벤트만큼 되돌려 계산한다.
     * 그래도 [consumptionNoisePercent]보다 더 음수면(감지 못한 보충 등) 해당 소비량은 null.
     * 운행 전후(시간 범위 밖)에 연결된 이벤트는 비용 목록에는 포함하지만 소비량 보정에는 쓰지 않는다.
     *
     * 진행 중 운행은 종료 잔량 대신 [currentLevels]를 쓸 수 있다. 계산할 것이 하나도 없으면 null.
     */
    fun tripCost(
        trip: TripEntity,
        events: List<EnergyEventEntity>,
        batteryCapacityKwh: Float? = null,
        currentLevels: EnergyLevels? = null,
    ): TripEnergyCost? {
        val tripEvents = events.filter { it.tripId == trip.id }
        val refuels = tripEvents.filter { it.kind == EnergyEventKind.REFUEL }
        val charges = tripEvents.filter { it.kind == EnergyEventKind.CHARGE }
        val until = trip.endedAt ?: Long.MAX_VALUE

        fun addedDuring(list: List<EnergyEventEntity>): Float = list
            .filter { it.detectedAt in trip.startedAt..until }
            .sumOf { max(0f, it.afterPercent - it.beforePercent).toDouble() }
            .toFloat()

        val fuelUsedPercent = usedPercent(
            trip.startFuelPercent,
            trip.endFuelPercent ?: currentLevels?.fuelPercent,
            addedDuring(refuels),
        )
        val batteryUsedPercent = usedPercent(
            trip.startBatteryPercent,
            trip.endBatteryPercent ?: currentLevels?.batteryPercent,
            addedDuring(charges),
        )
        val fuelUsed = fuelUsedPercent?.let(::fuel)
        val electricityUsed = batteryUsedPercent?.let { electricity(it, batteryCapacityKwh) }

        if (fuelUsed == null && electricityUsed == null && tripEvents.isEmpty()) return null
        return TripEnergyCost(
            fuelUsedLiters = fuelUsed?.amount,
            fuelCostWon = fuelUsed?.costWon,
            electricityUsedKwh = electricityUsed?.amount,
            electricityCostWon = electricityUsed?.costWon,
            refuels = refuels,
            charges = charges,
        )
    }

    private fun usedPercent(start: Float?, end: Float?, added: Float): Float? {
        if (start == null || end == null) return null
        val used = start - end + added
        if (!used.isFinite() || used < -consumptionNoisePercent) return null
        return max(0f, used)
    }

    private fun fuel(deltaPercent: Float): EnergyEstimate {
        val liters = deltaPercent / 100.0 * pricing.fuelTankLiters
        return EnergyEstimate(liters.toFloat(), (liters * pricing.gasolineWonPerLiter).roundToLong())
    }

    private fun electricity(deltaPercent: Float, batteryCapacityKwh: Float?): EnergyEstimate {
        val capacity = batteryCapacityKwh?.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: pricing.batteryCapacityKwh
        val kwh = deltaPercent / 100.0 * capacity
        return EnergyEstimate(kwh.toFloat(), (kwh * pricing.electricityWonPerKwh).roundToLong())
    }
}
