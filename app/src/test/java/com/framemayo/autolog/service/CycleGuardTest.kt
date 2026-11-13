package com.framemayo.autolog.service

import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #139: 한 주기의 예외가 수집 루프와 서비스를 끝내지 않는다. */
class CycleGuardTest {
    private val errors = mutableListOf<Exception>()
    private var recovered = 0
    private val guard = CycleGuard(onError = { errors += it }, onRecovered = { recovered++ })

    @Test
    fun `성공하면 결과를 돌려준다`() = runBlocking {
        assertEquals(3, guard.run { 1 + 2 })
        assertTrue(errors.isEmpty())
        assertEquals(0, recovered)
    }

    @Test
    fun `예외는 알리고 null을 돌려준다`() = runBlocking {
        assertNull(guard.run<Int> { throw SQLiteFullException("disk full") })
        assertEquals(1, errors.size)
    }

    @Test
    fun `취소는 삼키지 않는다`() = runBlocking {
        try {
            guard.run { throw CancellationException("stop") }
            fail("취소가 전파되지 않음")
        } catch (e: CancellationException) {
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun `DB 쓰기가 실패해도 1초 루프는 다음 주기에 이어서 동작한다`() = runBlocking {
        // 2·3번째 주기에 저장 공간 부족으로 heartbeat가 실패하는 상황
        val saved = mutableListOf<Int>()
        fun heartbeat(cycle: Int) {
            if (cycle == 2 || cycle == 3) throw SQLiteFullException("database or disk is full")
            saved += cycle
        }
        for (cycle in 1..5) guard.run { heartbeat(cycle) }

        assertEquals(listOf(1, 4, 5), saved)
        assertEquals(2, errors.size)
        assertEquals(1, recovered) // 실패 뒤 첫 성공에서 한 번만 RUNNING으로 되돌림
    }
}
