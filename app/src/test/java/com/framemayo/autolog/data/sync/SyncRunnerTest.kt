package com.framemayo.autolog.data.sync

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncRunnerTest {
    @Test fun runsDriversThenTripsAndReportsDoneOnlyWhenBothAre() = runBlocking {
        val order = mutableListOf<String>()
        val ok = SyncRunner({ order += "drivers"; true }, { order += "trips"; true })
        assertTrue(ok.run())
        assertEquals(listOf("drivers", "trips"), order)

        val driversPending = SyncRunner({ false }, { true })
        assertFalse(driversPending.run())
        val tripsPending = SyncRunner({ true }, { false })
        assertFalse(tripsPending.run())
    }

    @Test fun tripsStillRunWhenDriversFail() = runBlocking {
        var tripsRan = false
        val runner = SyncRunner({ false }, { tripsRan = true; true })
        assertFalse(runner.run())
        assertTrue(tripsRan)
    }

    @Test fun unexpectedExceptionIsTreatedAsRetryInsteadOfBreakingTheChain() = runBlocking {
        var tripsRan = false
        val runner = SyncRunner({ error("boom") }, { tripsRan = true; true })
        assertFalse(runner.run())
        assertTrue(tripsRan)
        assertFalse(SyncRunner({ true }, { throw IllegalStateException("db") }).run())
    }

    @Test fun cancellationIsNotSwallowed() {
        val runner = SyncRunner({ throw CancellationException("stopped") }, { true })
        val result = runCatching { runBlocking { runner.run() } }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun concurrentRunsNeverOverlap() = runBlocking {
        var active = 0
        var maxActive = 0
        val runner = SyncRunner(
            driverSync = {
                active++; maxActive = maxOf(maxActive, active)
                delay(20)
                active--; true
            },
            tripSync = { true },
        )
        List(5) { async { runner.run() } }.awaitAll()
        assertEquals(1, maxActive)
    }
}
