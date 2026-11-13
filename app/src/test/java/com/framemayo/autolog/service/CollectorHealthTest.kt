package com.framemayo.autolog.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** PR #174 리뷰: 한 경로의 회복이 다른 경로의 실패를 덮어쓰지 않는다. */
class CollectorHealthTest {
    private val health = CollectorHealth()

    @Test fun `실패한 경로가 없으면 RUNNING`() =
        assertEquals(CollectorStatus.RUNNING, health.report("sdk", ok = true))

    @Test fun `한 경로가 실패하면 RETRYING이고 그 경로가 회복하면 RUNNING`() {
        assertEquals(CollectorStatus.RETRYING, health.report("odometer", ok = false))
        assertEquals(CollectorStatus.RUNNING, health.report("odometer", ok = true))
    }

    @Test fun `다른 경로가 회복해도 실패 중인 경로가 있으면 RETRYING 유지`() {
        health.report("odometer sdk", ok = false)
        assertEquals(CollectorStatus.RETRYING, health.report("gear", ok = false))
        assertEquals(CollectorStatus.RETRYING, health.report("gear", ok = true))
        assertEquals(CollectorStatus.RUNNING, health.report("odometer sdk", ok = true))
    }
}
