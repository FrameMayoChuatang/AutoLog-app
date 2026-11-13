package com.framemayo.autolog.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OdometerFilterTest {
    private val s = 1_000L

    @Test fun `무효 값은 거부`() {
        val f = OdometerFilter()
        assertNull(f.accept(null, 0))
        assertNull(f.accept(0f, 0))
        assertNull(f.accept(-1f, 0))
        assertNull(f.accept(Float.NaN, 0))
        assertNull(f.accept(Float.POSITIVE_INFINITY, 0))
        assertNull(f.current)
    }

    @Test fun `정상 증가와 같은 값은 통과`() {
        val f = OdometerFilter()
        assertEquals(1270f, f.accept(1270f, 0))
        assertEquals(1270f, f.accept(1270f, s))
        assertEquals(1270.1f, f.accept(1270.1f, 2 * s))
    }

    @Test fun `튀는 값 하나 이후에도 정상 값이 계속 수신됨`() {
        val f = OdometerFilter()
        f.accept(1270f, 0)
        assertNull(f.accept(99_999f, s)) // 1초에 9만 km 증가: 거부
        assertEquals(1270f, f.current)
        assertEquals(1270.2f, f.accept(1270.2f, 2 * s))
        assertEquals(1270.4f, f.accept(1270.4f, 3 * s))
    }

    @Test fun `시연처럼 한 번에 수십 km 올리는 값은 통과`() {
        val f = OdometerFilter()
        f.accept(1000f, 0)
        assertEquals(1012f, f.accept(1012f, s))
        assertEquals(1060f, f.accept(1060f, 2 * s))
    }

    @Test fun `오래 지난 뒤의 큰 증가는 속도 상한 안이면 통과`() {
        val f = OdometerFilter()
        f.accept(1000f, 0)
        // 2시간 뒤 +500 km (300 km/h × 2h + 100 = 700 이내)
        assertEquals(1500f, f.accept(1500f, 2 * 3_600 * s))
    }

    @Test fun `재시작 직후 늦게 온 이전 값 하나는 거부`() {
        val f = OdometerFilter()
        f.seed(1015f, 0)
        assertNull(f.accept(1010f, s))
        assertEquals(1015f, f.accept(1015f, 2 * s))
        assertEquals(1015f, f.current)
    }

    @Test fun `잘못 잡힌 큰 기준은 일관된 작은 값 3번 연속이면 낮춤`() {
        val f = OdometerFilter()
        f.seed(99_999f, 0) // 이미 튄 값이 기준이 된 상태
        assertNull(f.accept(1270f, s))
        assertNull(f.accept(1270.1f, 2 * s))
        assertEquals(1270.2f, f.accept(1270.2f, 3 * s))
        assertEquals(1270.3f, f.accept(1270.3f, 4 * s))
    }

    @Test fun `작은 값이 서로 이어지지 않으면 기준을 낮추지 않음`() {
        val f = OdometerFilter()
        f.seed(5000f, 0)
        assertNull(f.accept(1270f, s))
        assertNull(f.accept(900f, 2 * s)) // 감소: 연속 끊김
        assertNull(f.accept(3000f, 3 * s)) // 1초에 2,100 km 증가: 연속 끊김
        assertEquals(5000f, f.current)
    }

    @Test fun `기준 이상 값이 오면 작은 값 연속은 처음부터`() {
        val f = OdometerFilter()
        f.seed(2000f, 0)
        assertNull(f.accept(1000f, s))
        assertNull(f.accept(1000f, 2 * s))
        assertEquals(2000f, f.accept(2000f, 3 * s))
        assertNull(f.accept(1000f, 4 * s))
        assertNull(f.accept(1000f, 5 * s))
        assertEquals(2000f, f.current)
    }

    @Test fun `시연에서 한 번에 크게 올린 값은 이어서 3번 오면 받아들임`() {
        val f = OdometerFilter()
        f.accept(1000f, 0)
        assertNull(f.accept(1500f, s)) // 1초에 +500 km: 일단 거부
        assertNull(f.accept(1500f, 2 * s))
        assertEquals(1500.1f, f.accept(1500.1f, 3 * s)) // 같은 흐름 3번째: 새 기준
        assertEquals(1500.2f, f.accept(1500.2f, 4 * s))
    }

    @Test fun `튄 큰 값이 연속돼도 정상 값이 오면 정상 값이 바로 통과`() {
        val f = OdometerFilter()
        f.accept(1270f, 0)
        assertNull(f.accept(99_999f, s))
        assertNull(f.accept(99_999f, 2 * s))
        assertEquals(1270.1f, f.accept(1270.1f, 3 * s))
        assertNull(f.accept(99_999f, 4 * s)) // 연속이 끊겨 다시 처음부터
        assertEquals(1270.1f, f.current)
    }

    @Test fun `진행 중 운행이 있으면 작은 값이 이어져도 기준을 낮추지 않음`() {
        val f = OdometerFilter()
        f.accept(112_000f, 0)
        repeat(5) { assertNull(f.accept(1_000f, (it + 1) * s, allowDownward = false)) }
        assertEquals(112_000f, f.current)
        // 운행이 끝난 뒤 같은 흐름이 이어지면 그때 옮긴다.
        assertEquals(1_000f, f.accept(1_000f, 6 * s, allowDownward = true))
    }

    @Test fun `진행 중 운행이 있어도 위로 크게 바뀐 값은 확인되면 받아들임`() {
        val f = OdometerFilter()
        f.accept(1000f, 0)
        assertNull(f.accept(1500f, s, allowDownward = false))
        assertNull(f.accept(1500f, 2 * s, allowDownward = false))
        assertEquals(1500f, f.accept(1500f, 3 * s, allowDownward = false))
    }
}
