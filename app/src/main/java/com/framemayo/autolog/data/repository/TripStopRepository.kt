package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.local.TripStopDao
import com.framemayo.autolog.data.local.TripStopEntity
import com.framemayo.autolog.vehicle.MotionAction
import kotlinx.coroutines.flow.Flow

/** 운행 중 P 구간 저장. 열기·닫기는 멱등이라 같은 동작을 두 번 적용해도 행이 늘지 않는다. */
class TripStopRepository(private val dao: TripStopDao) {

    /** [MotionAction.OpenStop]·[MotionAction.CloseStop]을 반영한다. */
    suspend fun apply(action: MotionAction) {
        when (action) {
            is MotionAction.OpenStop -> open(action.tripId, action.startedAt, action.odometerKm)
            is MotionAction.CloseStop -> close(action.tripId, action.endedAt)
        }
    }

    /**
     * 이미 열린 구간이 있으면 새로 만들지 않고 그 구간을 돌려준다. 같은 시작 시각의 구간이 이미 있으면
     * (닫힌 뒤 같은 동작을 다시 적용한 경우) 그 구간을 돌려준다.
     */
    suspend fun open(tripId: Long, startedAt: Long, odometerKm: Float): TripStopEntity {
        dao.open(tripId)?.let { return it }
        dao.forTrip(tripId).firstOrNull { it.startedAt == startedAt }?.let { return it }
        val stop = TripStopEntity(tripId = tripId, startedAt = startedAt, odometerKm = odometerKm)
        return stop.copy(id = dao.insert(stop))
    }

    /** 열린 구간이 없으면 아무것도 하지 않는다. 종료 시각은 시작 시각보다 앞서지 않게 맞춘다. */
    suspend fun close(tripId: Long, endedAt: Long) {
        val open = dao.open(tripId) ?: return
        dao.close(tripId, maxOf(endedAt, open.startedAt))
    }

    suspend fun openStop(tripId: Long): TripStopEntity? = dao.open(tripId)

    suspend fun forTrip(tripId: Long): List<TripStopEntity> = dao.forTrip(tripId)

    fun observeForTrip(tripId: Long): Flow<List<TripStopEntity>> = dao.observeForTrip(tripId)
}
