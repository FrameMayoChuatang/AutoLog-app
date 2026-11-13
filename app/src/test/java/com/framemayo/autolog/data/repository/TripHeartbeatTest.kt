package com.framemayo.autolog.data.repository

import com.framemayo.autolog.data.fakeTripDao
import com.framemayo.autolog.data.local.TripDao
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.durationMs
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** #147: 값이 같으면 매초 DB를 조회·기록하지 않는다. */
class TripHeartbeatTest {
    private val calls = mutableMapOf<String, Int>()
    private val dao: TripDao = fakeTripDao().let { real ->
        Proxy.newProxyInstance(TripDao::class.java.classLoader, arrayOf(TripDao::class.java)) { _, m, a ->
            calls.merge(m.name, 1, Int::plus)
            try {
                m.invoke(real, *(a ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as TripDao
    }
    private var now = 1_000_000L
    private val trips = TripRepositoryImpl(dao, clock = { now })

    private fun count(name: String) = calls[name] ?: 0

    @Test
    fun `운행이 없으면 기록하지 않는다`() = runBlocking {
        trips.heartbeat(100f)
        assertEquals(0, count("heartbeat"))
    }

    @Test
    fun `누적거리가 같으면 매초 조회도 기록도 하지 않고 60초마다만 시각을 갱신한다`() = runBlocking {
        val id = trips.start(driverId = 1L, odometerKm = 100f).id
        calls.clear()

        repeat(59) {
            now += 1_000
            trips.heartbeat(100f)
        }
        assertEquals(0, count("heartbeat"))

        now += 1_000 // 시작 후 60초
        trips.heartbeat(100f)
        assertEquals(1, count("heartbeat"))
        assertEquals(now, dao.get(id)!!.lastSavedAt)

        calls.clear()
        repeat(30) {
            now += 1_000
            trips.heartbeat(100f)
        }
        assertEquals(0, count("active"))
        assertEquals(0, count("heartbeat"))
    }

    @Test
    fun `누적거리가 바뀌면 바로 기록한다`() = runBlocking {
        val id = trips.start(driverId = 1L, odometerKm = 100f).id
        calls.clear()

        now += 1_000
        trips.heartbeat(100.1f)
        now += 1_000
        trips.heartbeat(100.2f)

        assertEquals(2, count("heartbeat"))
        val saved = dao.get(id)!!
        assertEquals(100.2f, saved.lastOdometerKm)
        assertEquals(now, saved.lastSavedAt)
    }

    @Test
    fun `진행 중 운행 시간은 저장 시각이 멈춰 있어도 현재 시각까지 흐른다`() {
        val active = TripEntity(driverId = null, startedAt = 0L, startOdometerKm = 1f, lastOdometerKm = 1f, lastSavedAt = 60_000L)
        assertEquals(90_000L, active.durationMs(now = 90_000L))
        val ended = active.copy(endedAt = 120_000L)
        assertEquals(120_000L, ended.durationMs(now = 999_999L))
    }

    @Test
    fun `출발값보다 작은 누적거리는 저장하지 않고 종료도 마지막 실측값으로 한다`() = runBlocking {
        val id = trips.start(driverId = 1L, odometerKm = 112_000f).id
        now += 1_000
        trips.heartbeat(112_000.5f)
        now += 1_000
        trips.heartbeat(1_000f) // 콘솔에서 누적거리를 크게 낮춘 상황
        assertEquals(112_000.5f, dao.get(id)!!.lastOdometerKm)

        now += 1_000
        val ended = trips.end(1_000f)!!
        assertEquals(112_000.5f, ended.endOdometerKm)
        assertEquals(0.5f, ended.endOdometerKm!! - ended.startOdometerKm, 0.001f)
    }
}
