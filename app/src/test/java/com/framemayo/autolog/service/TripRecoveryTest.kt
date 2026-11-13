package com.framemayo.autolog.service

import com.framemayo.autolog.data.fakeTripDao
import com.framemayo.autolog.data.local.TripEndReason
import com.framemayo.autolog.data.local.TripStatus
import com.framemayo.autolog.data.repository.TripRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecoveryTest {
    private val dao = fakeTripDao()
    private var now = T0
    private val trips = TripRepositoryImpl(dao, clock = { now })
    private val recovery = TripRecovery(trips, activeTrip = { dao.active() })

    /** T0에 1000km로 시작해 T1에 1010km 스냅샷을 남기고 프로세스가 죽은 상황. */
    private suspend fun leftoverActiveTrip(): Long {
        val id = trips.start(driverId = 1L, odometerKm = 1000f).id
        now = T1
        trips.heartbeat(LAST_KM)
        return id
    }

    @Test
    fun `ACTIVE 운행이 없으면 아무것도 하지 않는다`() = runBlocking {
        assertEquals(RecoveryResult.None, recovery.recover(currentKm = 1010f, now = T1))
        assertFalse(recovery.awaitingOdometer)
    }

    @Test
    fun `짧은 공백 뒤 유효한 누적거리면 같은 운행을 이어 간다`() = runBlocking {
        val id = leftoverActiveTrip()
        now = T1 + 5 * MIN

        val result = recovery.recover(currentKm = 1012f, now = now)

        assertTrue(result is RecoveryResult.Resumed)
        val trip = dao.get(id)!!
        assertEquals(TripStatus.ACTIVE, trip.status)
        assertEquals(1L, trip.sourceRevision)
        assertNull(trip.endOdometerKm)
        assertFalse(recovery.awaitingOdometer)
    }

    @Test
    fun `재개 창 경계(10분)까지는 이어 간다`() = runBlocking {
        val id = leftoverActiveTrip()
        val result = recovery.recover(currentKm = LAST_KM, now = T1 + TripRecovery.RESUME_WINDOW_MS)
        assertTrue(result is RecoveryResult.Resumed)
        assertEquals(TripStatus.ACTIVE, dao.get(id)!!.status)
    }

    @Test
    fun `오래 지났어도 차가 움직이지 않았으면 마지막 스냅샷을 실측 종료값으로 완료한다`() = runBlocking {
        val id = leftoverActiveTrip()
        now = T1 + 60 * MIN

        val result = recovery.recover(currentKm = LAST_KM + 0.04f, now = now)

        assertTrue(result is RecoveryResult.Completed)
        val trip = dao.get(id)!!
        assertEquals(TripStatus.COMPLETED, trip.status)
        assertEquals(TripEndReason.RECOVERED, trip.endedBy)
        assertEquals(LAST_KM, trip.endOdometerKm)
        assertEquals(T1, trip.endedAt)
        assertEquals(3L, trip.sourceRevision) // ACTIVE 1 → INTERRUPTED 2 → COMPLETED 3
        assertEquals(trip, (result as RecoveryResult.Completed).trip)
        assertFalse(recovery.awaitingOdometer)
    }

    @Test
    fun `오래 지났고 차가 움직였으면 INTERRUPTED로 두고 이후에도 승격하지 않는다`() = runBlocking {
        val id = leftoverActiveTrip()
        now = T1 + 60 * MIN

        val result = recovery.recover(currentKm = 1050f, now = now)

        assertTrue(result is RecoveryResult.Interrupted)
        val trip = dao.get(id)!!
        assertEquals(TripStatus.INTERRUPTED, trip.status)
        assertEquals(LAST_KM, trip.endOdometerKm) // 마지막 스냅샷, 현재값(1050)으로 추정하지 않음
        assertEquals(T1, trip.endedAt)
        assertNull(trip.endedBy)
        assertFalse(recovery.awaitingOdometer)

        assertEquals(RecoveryResult.None, recovery.onFirstValidOdometer(LAST_KM, now))
        assertEquals(TripStatus.INTERRUPTED, dao.get(id)!!.status)
    }

    @Test
    fun `부팅 직후 0 누적거리는 최근 운행이면 중단하지 않고 판단을 보류한다`() = runBlocking {
        val id = leftoverActiveTrip()
        now = T1 + MIN

        assertTrue(recovery.recover(currentKm = 0f, now = now) is RecoveryResult.Deferred)
        assertEquals(TripStatus.ACTIVE, dao.get(id)!!.status)
        assertTrue(recovery.awaitingOdometer)
    }

    @Test
    fun `역행 누적거리도 재개하지 않는다`() = runBlocking {
        leftoverActiveTrip()
        assertTrue(recovery.recover(currentKm = 900f, now = T1 + MIN) is RecoveryResult.Interrupted)
        assertTrue(recovery.awaitingOdometer)
    }

    @Test
    fun `오래 지났는데 누적거리를 못 읽었으면 INTERRUPTED 후 같은 값이 들어올 때 한 번만 승격한다`() = runBlocking {
        val id = leftoverActiveTrip()
        now = T1 + 20 * MIN

        assertTrue(recovery.recover(currentKm = null, now = now) is RecoveryResult.Interrupted)
        assertEquals(TripStatus.INTERRUPTED, dao.get(id)!!.status)
        assertTrue(recovery.awaitingOdometer)

        now += MIN
        val upgraded = recovery.onFirstValidOdometer(LAST_KM + 0.02f, now)

        assertTrue(upgraded is RecoveryResult.Completed)
        val trip = dao.get(id)!!
        assertEquals(TripStatus.COMPLETED, trip.status)
        assertEquals(TripEndReason.RECOVERED, trip.endedBy)
        assertEquals(LAST_KM, trip.endOdometerKm) // 스냅샷 값, 새로 읽은 값 아님
        assertEquals(T1, trip.endedAt) // 스냅샷 시각, 재시작 시각 아님
        assertEquals(3L, trip.sourceRevision)
        assertFalse(recovery.awaitingOdometer)
        assertEquals(RecoveryResult.None, recovery.onFirstValidOdometer(LAST_KM, now))
        assertEquals(3L, dao.get(id)!!.sourceRevision)
    }

    @Test
    fun `오래 지났는데 누적거리를 못 읽었고 나중에 움직인 값이 오면 승격하지 않고 판단을 끝낸다`() = runBlocking {
        val id = leftoverActiveTrip()
        recovery.recover(currentKm = null, now = T1 + 20 * MIN)

        assertEquals(RecoveryResult.None, recovery.onFirstValidOdometer(1030f, T1 + 21 * MIN))
        assertEquals(TripStatus.INTERRUPTED, dao.get(id)!!.status)
        assertFalse(recovery.awaitingOdometer)
        // 첫 유효값에서 판단이 끝났으므로 이후 같은 값이 와도 승격하지 않는다.
        assertEquals(RecoveryResult.None, recovery.onFirstValidOdometer(LAST_KM, T1 + 22 * MIN))
        assertEquals(TripStatus.INTERRUPTED, dao.get(id)!!.status)
    }

    @Test
    fun `이번 실행에서 중단시키지 않은 INTERRUPTED 운행은 승격하지 않는다`() = runBlocking {
        val id = leftoverActiveTrip()
        trips.recoverInterrupted() // 이전 실행에서 이미 INTERRUPTED가 된 운행
        now = T1 + 60 * MIN

        assertEquals(RecoveryResult.None, recovery.recover(currentKm = null, now = now))
        assertFalse(recovery.awaitingOdometer)
        assertEquals(RecoveryResult.None, recovery.onFirstValidOdometer(LAST_KM, now))
        assertEquals(TripStatus.INTERRUPTED, dao.get(id)!!.status)
    }

    // ── #145: SDK 초기화가 늦어 시작 때 누적거리를 못 읽은 경우 ──

    @Test
    fun `SDK가 5초 넘게 늦어도 최근 운행은 중단하지 않는다`() = runBlocking {
        val id = leftoverActiveTrip()
        now = T1 + 10_000L // 10초 만에 재시작했지만 SDK가 아직 준비 안 됨

        val result = recovery.recover(currentKm = null, now = now)

        assertTrue(result is RecoveryResult.Deferred)
        val trip = dao.get(id)!!
        assertEquals(TripStatus.ACTIVE, trip.status)
        assertEquals(1L, trip.sourceRevision) // 서버로 보낼 변경 없음
        assertTrue(recovery.awaitingOdometer)
    }

    @Test
    fun `보류 중 SDK가 살아나 움직인 값이 와도 창 안이면 같은 운행을 이어 간다`() = runBlocking {
        val id = leftoverActiveTrip()
        recovery.recover(currentKm = null, now = T1 + 10_000L)

        val result = recovery.onFirstValidOdometer(1012f, T1 + 40_000L)

        assertTrue(result is RecoveryResult.Resumed)
        assertEquals(TripStatus.ACTIVE, dao.get(id)!!.status)
        assertFalse(recovery.awaitingOdometer)
    }

    @Test
    fun `보류 중 재개 창 전에는 주기 확인으로 중단하지 않는다`() = runBlocking {
        val id = leftoverActiveTrip()
        recovery.recover(currentKm = null, now = T1 + 10_000L)

        assertEquals(RecoveryResult.None, recovery.onTick(T1 + 9 * MIN))
        assertEquals(TripStatus.ACTIVE, dao.get(id)!!.status)
        assertTrue(recovery.awaitingOdometer)
    }

    @Test
    fun `재개 창이 끝날 때까지 못 읽으면 중단하고 같은 값이 오면 늦게 승격한다`() = runBlocking {
        val id = leftoverActiveTrip()
        recovery.recover(currentKm = null, now = T1 + 10_000L)

        val timedOut = recovery.onTick(T1 + 11 * MIN)
        assertTrue(timedOut is RecoveryResult.Interrupted)
        assertEquals(TripStatus.INTERRUPTED, dao.get(id)!!.status)
        assertTrue(recovery.awaitingOdometer)

        now = T1 + 12 * MIN
        assertTrue(recovery.onFirstValidOdometer(LAST_KM, now) is RecoveryResult.Completed)
        assertEquals(TripStatus.COMPLETED, dao.get(id)!!.status)
        assertEquals(T1, dao.get(id)!!.endedAt)
    }

    @Test
    fun `보류 중 창을 넘겨 같은 값이 오면 실측 종료값으로 완료한다`() = runBlocking {
        val id = leftoverActiveTrip()
        recovery.recover(currentKm = null, now = T1 + 10_000L)
        now = T1 + 15 * MIN

        assertTrue(recovery.onFirstValidOdometer(LAST_KM, now) is RecoveryResult.Completed)
        assertEquals(LAST_KM, dao.get(id)!!.endOdometerKm)
        assertFalse(recovery.awaitingOdometer)
    }

    @Test
    fun `보류 중 창을 넘겨 움직인 값이 오면 중단하고 더 기다리지 않는다`() = runBlocking {
        val id = leftoverActiveTrip()
        recovery.recover(currentKm = null, now = T1 + 10_000L)
        now = T1 + 15 * MIN

        assertTrue(recovery.onFirstValidOdometer(1030f, now) is RecoveryResult.Interrupted)
        assertEquals(TripStatus.INTERRUPTED, dao.get(id)!!.status)
        assertFalse(recovery.awaitingOdometer)
    }

    @Test
    fun `보류 중 역행 값이 오면 중단한다`() = runBlocking {
        val id = leftoverActiveTrip()
        recovery.recover(currentKm = null, now = T1 + 10_000L)

        assertTrue(recovery.onFirstValidOdometer(900f, T1 + 20_000L) is RecoveryResult.Interrupted)
        assertEquals(TripStatus.INTERRUPTED, dao.get(id)!!.status)
        assertFalse(recovery.awaitingOdometer)
    }

    @Test
    fun `보류 중 운전자가 운행을 끝냈으면 주기 확인은 아무것도 하지 않는다`() = runBlocking {
        val id = leftoverActiveTrip()
        recovery.recover(currentKm = null, now = T1 + 10_000L)
        now = T1 + MIN
        trips.end(LAST_KM)

        assertEquals(RecoveryResult.None, recovery.onTick(T1 + 20 * MIN))
        assertFalse(recovery.awaitingOdometer)
        assertEquals(TripStatus.COMPLETED, dao.get(id)!!.status)
    }

    private companion object {
        const val MIN = 60_000L
        const val T0 = 1_700_000_000_000L
        const val T1 = T0 + 30 * MIN
        const val LAST_KM = 1010f
    }
}
