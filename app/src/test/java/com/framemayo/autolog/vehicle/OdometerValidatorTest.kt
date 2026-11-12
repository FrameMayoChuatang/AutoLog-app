package com.framemayo.autolog.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OdometerValidatorTest {
    @Test fun `부팅 직후 0은 무효`() = assertFalse(OdometerValidator.isValid(0f, null))
    @Test fun `null은 무효`() = assertFalse(OdometerValidator.isValid(null, 100f))
    @Test fun `감소값은 무효`() = assertFalse(OdometerValidator.isValid(99.9f, 100f))
    @Test fun `첫 정상값은 유효`() = assertTrue(OdometerValidator.isValid(117430f, null))
    @Test fun `같은 값은 유효`() = assertTrue(OdometerValidator.isValid(100f, 100f))
}

