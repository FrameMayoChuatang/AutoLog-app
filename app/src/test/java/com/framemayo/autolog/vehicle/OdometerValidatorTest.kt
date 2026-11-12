package com.framemayo.autolog.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OdometerValidatorTest {
    @Test fun `유한하지 않은 값은 첫 수신과 후속 수신 모두 무효`() {
        for (km in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(OdometerValidator.isValid(km, null))
            assertFalse(OdometerValidator.isValid(km, 100f))
        }
    }
    @Test fun `비정상 입력을 버린 뒤 정상 증가값은 유효`() {
        val values = listOf(100f, Float.POSITIVE_INFINITY, Float.NaN, 100.5f)
        var last: Float? = null
        val accepted = values.filter { km ->
            OdometerValidator.isValid(km, last).also { if (it) last = km }
        }
        org.junit.Assert.assertEquals(listOf(100f, 100.5f), accepted)
    }
    @Test fun `부팅 직후 0은 무효`() = assertFalse(OdometerValidator.isValid(0f, null))
    @Test fun `null은 무효`() = assertFalse(OdometerValidator.isValid(null, 100f))
    @Test fun `감소값은 무효`() = assertFalse(OdometerValidator.isValid(99.9f, 100f))
    @Test fun `첫 정상값은 유효`() = assertTrue(OdometerValidator.isValid(117430f, null))
    @Test fun `같은 값은 유효`() = assertTrue(OdometerValidator.isValid(100f, 100f))
}

