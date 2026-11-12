package com.framemayo.autolog.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GearCatchUpTest {
    private val noWait: suspend (Long) -> Unit = { yield() }

    /** 리스너가 아무 값도 보내지 않는 상황(부팅 직후 기본값과 같은 기어를 설정). */
    private fun silentListener(): Flow<VehicleGear?> = MutableSharedFlow()

    @Test
    fun `gear set to boot default without listener event is filled by reading current value`() = runBlocking {
        var reads = 0
        val gear = withTimeout(5_000) {
            gearWithCatchUp(silentListener(), read = { reads++; VehicleGear.GEAR_PARK }, delayFn = noWait).first()
        }
        assertEquals(VehicleGear.GEAR_PARK, gear)
        assertEquals(1, reads)
    }

    @Test
    fun `keeps reading while value is unknown and stops once known`() = runBlocking {
        val answers = listOf(null, VehicleGear.GEAR_UNKNOWN, null, VehicleGear.GEAR_DRIVE)
        var reads = 0
        val read: suspend () -> VehicleGear? = { answers.getOrElse(reads++) { VehicleGear.GEAR_REVERSE } }
        val first = withTimeout(5_000) { gearWithCatchUp(silentListener(), read, delayFn = noWait).first() }
        assertEquals(VehicleGear.GEAR_DRIVE, first)
        assertEquals(4, reads)
    }

    @Test
    fun `read failure is treated as unknown and retried`() = runBlocking {
        var reads = 0
        val read: suspend () -> VehicleGear? = { if (reads++ == 0) error("SDK not ready") else VehicleGear.GEAR_PARK }
        val gear = withTimeout(5_000) { gearWithCatchUp(silentListener(), read, delayFn = noWait).first() }
        assertEquals(VehicleGear.GEAR_PARK, gear)
        assertEquals(2, reads)
    }

    @Test
    fun `listener value wins and polling stops when listener already reported`() = runBlocking {
        var reads = 0
        val listener = flow {
            emit(VehicleGear.GEAR_DRIVE)
            emit(VehicleGear.GEAR_PARK)
        }
        val values = withTimeout(5_000) {
            gearWithCatchUp(listener, read = { reads++; VehicleGear.GEAR_REVERSE }, delayFn = { kotlinx.coroutines.delay(50) }).toList()
        }
        // 리스너 값만 나오고, 조회 결과(R)는 리스너가 값을 준 뒤라 버려진다.
        assertFalse(VehicleGear.GEAR_REVERSE in values)
        assertEquals(listOf(VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_PARK), values)
    }

    @Test
    fun `stale read does not override a newer listener value`() = runBlocking {
        val listener = MutableSharedFlow<VehicleGear?>()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val read: suspend () -> VehicleGear? = {
            readStarted.complete(Unit)
            releaseRead.await()
            VehicleGear.GEAR_PARK // 조회가 끝나기 전에 운전자가 D로 바꿨다.
        }
        val collected = async { gearWithCatchUp(listener, read, delayFn = noWait).take(1).toList() }
        withTimeout(5_000) { readStarted.await() }
        while (listener.subscriptionCount.value == 0) yield()
        listener.emit(VehicleGear.GEAR_DRIVE)
        releaseRead.complete(Unit)
        assertEquals(listOf(VehicleGear.GEAR_DRIVE), withTimeout(5_000) { collected.await() })
    }

    @Test
    fun `later changes after catch-up still come from the listener`() = runBlocking {
        val listener = MutableSharedFlow<VehicleGear?>()
        val collected = async {
            gearWithCatchUp(listener, read = { VehicleGear.GEAR_PARK }, delayFn = noWait).take(3).toList()
        }
        while (listener.subscriptionCount.value == 0) yield()
        repeat(20) { yield() }
        listener.emit(VehicleGear.GEAR_DRIVE)
        listener.emit(VehicleGear.GEAR_PARK)
        assertEquals(
            listOf(VehicleGear.GEAR_PARK, VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_PARK),
            withTimeout(5_000) { collected.await() },
        )
    }

    @Test
    fun `unknown helper treats null and GEAR_UNKNOWN as unknown`() {
        assertTrue(null.isUnknown())
        assertTrue(VehicleGear.GEAR_UNKNOWN.isUnknown())
        assertFalse(VehicleGear.GEAR_PARK.isUnknown())
    }
}
