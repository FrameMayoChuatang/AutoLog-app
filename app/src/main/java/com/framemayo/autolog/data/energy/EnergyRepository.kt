package com.framemayo.autolog.data.energy

import com.framemayo.autolog.data.local.EnergyEventDao
import com.framemayo.autolog.data.local.EnergyEventEntity
import com.framemayo.autolog.data.local.EnergyLevels
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.vehicle.DetectedEnergyEvent
import kotlinx.coroutines.flow.Flow

/** 주유·충전 이벤트를 추정 양·금액과 함께 로컬에만 저장한다(서버 전송 없음). */
class EnergyRepository(
    private val dao: EnergyEventDao,
    val estimator: EnergyCostEstimator = EnergyCostEstimator(),
    recentLimit: Int = DEFAULT_RECENT_LIMIT,
) {
    /** 최근 이벤트(감지 시각 내림차순). */
    val recent: Flow<List<EnergyEventEntity>> = dao.observeRecent(recentLimit)

    /**
     * 감지 이벤트를 저장한다. [batteryCapacityKwh]는 SDK 값(없으면 null → 설정 기본값).
     * 상승이 없어 추정할 수 없으면 양·금액은 null로 저장한다.
     */
    suspend fun record(event: DetectedEnergyEvent, tripId: Long?, batteryCapacityKwh: Float? = null): EnergyEventEntity {
        val estimate = estimator.estimate(event.kind, event.before, event.after, batteryCapacityKwh)
        val entity = EnergyEventEntity(
            kind = event.kind,
            tripId = tripId,
            detectedAt = event.detectedAt,
            odometerKm = event.odometerKm,
            beforePercent = event.before,
            afterPercent = event.after,
            estimatedAmount = estimate?.amount,
            estimatedCostWon = estimate?.costWon,
        )
        return entity.copy(id = dao.insert(entity))
    }

    suspend fun forTrip(tripId: Long): List<EnergyEventEntity> = dao.forTrip(tripId)

    /** 운행 소비량·비용과 그 운행에 연결된 주유·충전 이벤트. 계산할 것이 없으면 null. */
    suspend fun tripCost(
        trip: TripEntity,
        batteryCapacityKwh: Float? = null,
        currentLevels: EnergyLevels? = null,
    ): TripEnergyCost? = estimator.tripCost(trip, dao.forTrip(trip.id), batteryCapacityKwh, currentLevels)

    companion object {
        const val DEFAULT_RECENT_LIMIT = 50
    }
}
