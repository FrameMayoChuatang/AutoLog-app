package com.framemayo.autolog.vehicle

import android.app.Application
import com.framemayo.autolog.data.remote.TestServer
import com.framemayo.autolog.service.resubscribing
import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConsoleOdometerSourceTest {
    private fun payload(km: String = "2.5", bridge: Boolean = true, active: Boolean = true) =
        """{"odometer":{"traveled_distance_km":$km,"bridge_ready":$bridge,"emulator_active":$active}}"""

    @Test fun preservesKilometersAndValidBoundaries() {
        listOf(0f, 2.5f, 1_000_000f).forEach { km ->
            assertEquals(km, parseConsoleOdometer(payload(km.toString())), 0f)
        }
    }

    @Test fun rejectsInvalidResponsesInsteadOfInventingDistance() {
        val invalid = listOf("broken", "{}", """{"odometer":{}}""",
            payload("null"), payload("\"2\""), payload("-1"), payload("1e999"),
            payload("1000001"), payload(bridge = false), payload(active = false))
        invalid.forEach { body ->
            assertThrows(body, IOException::class.java) { parseConsoleOdometer(body) }
        }
    }

    @Test fun readsStateWithGetAndDoesNotReuseValueAfterFailure() = runBlocking {
        val count = AtomicInteger()
        val server = TestServer { method, path, auth, _ ->
            assertEquals("GET", method)
            assertEquals("/vehicle/state", path)
            assertNull(auth)
            if (count.incrementAndGet() == 1) 200 to payload() else 503 to "{}"
        }
        try {
            val source = ConsoleOdometerSource(URL(server.url + "/vehicle/state"))
            assertEquals(2.5f, source.currentKm(), 0f)
            try {
                source.currentKm()
                fail("실패한 조회에서 이전 값을 반환하면 안 됩니다")
            } catch (_: IOException) { }
            assertEquals(2, count.get())
        } finally { server.stop() }
    }

    @Test fun rejectsRedirectWithoutFollowingAnotherSource() = runBlocking {
        val count = AtomicInteger()
        val server = TestServer { _, _, _, _ -> count.incrementAndGet(); 302 to "{}" }
        server.responseHeaders["Location"] = server.url + "/other"
        try {
            try {
                ConsoleOdometerSource(URL(server.url)).currentKm()
                fail("다른 출처로 리다이렉트하면 안 됩니다")
            } catch (_: IOException) { }
            assertEquals(1, count.get())
        } finally { server.stop() }
    }

    @Test fun pollsChangedDistanceAndStopsWhenCollectionEnds() = runBlocking {
        val count = AtomicInteger()
        val server = TestServer { _, _, _, _ -> 200 to payload(count.incrementAndGet().toString()) }
        try {
            val source = ConsoleOdometerSource(URL(server.url), pollIntervalMs = 10)
            val values = withTimeout(5_000) { source.updates().take(2).toList() }
            assertEquals(listOf(1f, 2f), values)
            delay(50)
            assertEquals(2, count.get())
        } finally { server.stop() }
    }

    @Test fun existingSupervisorRetriesFailureAndRecoversOnFreshValue() = runBlocking {
        val count = AtomicInteger()
        var failures = 0
        var recovered = 0
        val server = TestServer { _, _, _, _ ->
            when (count.incrementAndGet()) {
                1 -> 200 to payload("2")
                2 -> 503 to "{}"
                else -> 200 to payload("3")
            }
        }
        try {
            val source = ConsoleOdometerSource(URL(server.url), pollIntervalMs = 10)
            val values = withTimeout(5_000) {
                source.updates().resubscribing(
                    delayFn = {},
                    onFailure = { _, error, _ -> assertTrue(error is IOException); failures++ },
                    onRecovered = { recovered++ },
                ).take(2).toList()
            }
            assertEquals(listOf(2f, 3f), values)
            assertEquals(1, failures)
            assertEquals(1, recovered)
            assertEquals(3, count.get())
        } finally { server.stop() }
    }
}
