package com.framemayo.autolog.service

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorSupervisorTest {
    private val delays = mutableListOf<Long>()
    private val recordDelay: suspend (Long) -> Unit = { delays += it }

    @Test
    fun `재시도 간격은 1초부터 두 배씩 늘고 30초에서 멈춘다`() = runBlocking {
        var calls = 0
        val attempts = mutableListOf<Int>()

        val result = retryWithBackoff(delayFn = recordDelay, onRetry = { n, _, _ -> attempts += n }) {
            if (++calls <= 8) throw IllegalStateException("init failed")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(9, calls)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
        assertEquals((1..8).toList(), attempts)
    }

    @Test
    fun `첫 시도에 성공하면 기다리지 않는다`() = runBlocking {
        assertEquals(1, retryWithBackoff(delayFn = recordDelay) { 1 })
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `블록이 던진 취소는 재시도하지 않고 전파한다`() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                retryWithBackoff<Unit>(delayFn = recordDelay) {
                    calls++
                    throw CancellationException("stop")
                }
            }
        }
        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `대기 중 취소되면 더 시도하지 않는다`() = runBlocking {
        var calls = 0
        val waiting = CompletableDeferred<Unit>()
        val job = launch {
            retryWithBackoff<Unit>(delayFn = { waiting.complete(Unit); awaitCancellation() }) {
                calls++
                throw IOException("x")
            }
        }
        waiting.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    /** n번째 구독마다 [script]가 정한 동작을 하는 업스트림. */
    private fun scripted(script: suspend kotlinx.coroutines.flow.FlowCollector<Int>.(n: Int) -> Unit): Pair<Flow<Int>, () -> Int> {
        var subscriptions = 0
        return flow { script(++subscriptions) } to { subscriptions }
    }

    @Test
    fun `실패하면 재구독하고 값을 받으면 백오프를 초기화한다`() = runBlocking {
        val (upstream, subscriptions) = scripted { n ->
            when (n) {
                1, 2, 3 -> throw IOException("failed $n")
                4 -> { emit(1); throw IOException("failed after value") }
                5 -> { emit(2); emit(3) }
            }
        }
        val failures = mutableListOf<Int>()
        var recovered = 0

        val values = upstream.resubscribing(
            delayFn = recordDelay,
            onFailure = { attempt, _, _ -> failures += attempt },
            onRecovered = { recovered++ },
        ).toList()

        assertEquals(listOf(1, 2, 3), values)
        assertEquals(5, subscriptions())
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 1_000L), delays)
        assertEquals(listOf(1, 2, 3, 1), failures)
        assertEquals(2, recovered) // 4번째·5번째 구독의 첫 값
    }

    @Test
    fun `재구독 간격도 30초에서 멈춘다`() = runBlocking {
        val (upstream, _) = scripted { n -> if (n <= 7) throw IOException("x") else emit(n) }
        assertEquals(listOf(8), upstream.resubscribing(delayFn = recordDelay).toList())
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun `재구독 직전 1회 조회 값을 먼저 내보내고 null·실패는 건너뛴다`() = runBlocking {
        val (upstream, _) = scripted { n -> if (n <= 3) throw IOException("x") else emit(n * 10) }
        var primes = 0

        val values = upstream.resubscribing(
            delayFn = recordDelay,
            prime = {
                when (++primes) {
                    1 -> 999
                    2 -> null
                    else -> throw IOException("read failed")
                }
            },
        ).toList()

        assertEquals(listOf(999, 40), values)
        assertEquals(3, primes)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays) // 조회 값은 백오프를 초기화하지 않는다
    }

    @Test
    fun `다운스트림 예외는 재구독하지 않고 전파한다`() {
        val (upstream, subscriptions) = scripted { emit(1); emit(2) }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { upstream.resubscribing(delayFn = recordDelay).collect { error("ui failed") } }
        }
        assertEquals(1, subscriptions())
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `업스트림이 던진 취소는 재구독하지 않고 전파한다`() {
        val (upstream, subscriptions) = scripted { throw CancellationException("stop") }
        var failures = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { upstream.resubscribing(delayFn = recordDelay, onFailure = { _, _, _ -> failures++ }).toList() }
        }
        assertEquals(1, subscriptions())
        assertEquals(0, failures)
    }

    @Test
    fun `재구독 대기 중 취소되면 멈춘다`() = runBlocking {
        val (upstream, subscriptions) = scripted { throw IOException("x") }
        val waiting = CompletableDeferred<Unit>()
        val job = launch {
            upstream.resubscribing(delayFn = { waiting.complete(Unit); awaitCancellation() }).collect {}
        }
        waiting.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, subscriptions())
    }
}
