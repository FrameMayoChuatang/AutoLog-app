package com.framemayo.autolog.service

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.framemayo.autolog.data.local.EnergyLevels
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripStatus
import com.framemayo.autolog.data.local.TripTrigger
import com.framemayo.autolog.data.repository.TripRepository
import com.framemayo.autolog.vehicle.AutoTripStarter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [AutoTripStarter]의 결정을 실제 운행 생성으로 옮긴다(#91). 모든 입력을 하나의 [Mutex]로 직렬화하므로
 * 누적거리가 동시에 들어와도 운행은 한 번만 만들어진다.
 *
 * 자동 시작 운행은 운전자 미지정(driverId = null), [TripTrigger.AUTO]이며 운전자는 기존 "나중에 지정" 흐름으로 정한다.
 * 차량 연동 여부는 보지 않는다. 미연동이면 로컬에만 기록되고 동기화는 기존 NOT_LINKED 규칙이 보류한다.
 */
class AutoTripController(
    private val trips: TripRepository,
    private val energy: () -> EnergyLevels? = { null },
    private val starter: AutoTripStarter = AutoTripStarter(),
    private val onError: (Throwable) -> Unit = {},
) {
    private val mutex = Mutex()
    private var activeTripId: Long? = null

    /** 서비스 시작 시 1회, recoverInterrupted 이후·누적거리 전달 전에 호출한다. */
    suspend fun seed() = mutex.withLock {
        val active = trips.active.first()
        activeTripId = active?.id
        starter.seed(trips.lastEnded(), active)
    }

    suspend fun onGear(gear: VehicleGear?, now: Long) = mutex.withLock { starter.onGear(gear, now) }

    /**
     * 유효성 검사를 통과한 누적거리. 운행을 자동으로 시작했으면 그 운행을 돌려준다(알림·로그용).
     * 그 사이 수동 운행이 이미 생겨 저장소가 기존 운행을 돌려주면 null.
     */
    suspend fun onOdometer(km: Float, now: Long): TripEntity? = mutex.withLock {
        val decision = starter.onOdometer(km, now) ?: return@withLock null
        val trip = try {
            trips.start(
                driverId = null,
                odometerKm = decision.startKm,
                startedAt = decision.startedAt,
                trigger = TripTrigger.AUTO,
                energy = runCatching(energy).getOrNull(),
            )
        } catch (e: CancellationException) {
            starter.onStartFailed()
            throw e
        } catch (e: Exception) {
            starter.onStartFailed()
            onError(e)
            return@withLock null
        }
        val isNew = trip.id != activeTripId && trip.startedBy == TripTrigger.AUTO && trip.startedAt == decision.startedAt
        activeTripId = trip.id
        trip.takeIf { isNew }
    }

    /**
     * [TripRepository.active]의 매 방출을 그대로 넘긴다. 수동 시작·종료와 다른 경로의 자동 종료를 여기서 알아챈다.
     * null 방출은 알고 있던 운행이 실제로 끝났는지 저장소에서 확인한 뒤에만 종료로 처리한다(늦게 도착한 오래된 방출 무시).
     */
    suspend fun onActiveTrip(trip: TripEntity?) = mutex.withLock {
        if (trip != null) {
            activeTripId = trip.id
            starter.onTripActive()
            return@withLock
        }
        val id = activeTripId ?: return@withLock
        val stored = trips.get(id)
        if (stored?.status == TripStatus.ACTIVE) return@withLock
        activeTripId = null
        starter.onTripEnded(stored)
    }

    /** 종료된 운행을 직접 알려줄 때(예: 서비스 내부 자동 종료 직후). [onActiveTrip] 경로와 중복 호출돼도 안전하다. */
    suspend fun onTripEnded(trip: TripEntity) = mutex.withLock {
        if (activeTripId != null && activeTripId != trip.id) return@withLock
        activeTripId = null
        starter.onTripEnded(trip)
    }
}
