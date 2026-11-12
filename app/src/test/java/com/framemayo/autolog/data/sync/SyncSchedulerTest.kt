package com.framemayo.autolog.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulerTest {
    @Test fun retryDelayDoublesFromThirtySecondsAndStopsAtFiveMinutes() {
        val delays = (1..8).map { SyncScheduler.retryDelaySeconds(it) }
        assertEquals(listOf(30L, 60L, 120L, 240L, 300L, 300L, 300L, 300L), delays)
    }

    @Test fun retryDelayNeverExceedsFiveMinutesEvenForHugeAttemptCounts() {
        assertEquals(300L, SyncScheduler.retryDelaySeconds(1_000))
        assertTrue((1..1_000).all { SyncScheduler.retryDelaySeconds(it) in 30L..300L })
    }

    @Test fun invalidAttemptCountsFallBackToTheFirstDelay() {
        assertEquals(30L, SyncScheduler.retryDelaySeconds(0))
        assertEquals(30L, SyncScheduler.retryDelaySeconds(-3))
    }
}
