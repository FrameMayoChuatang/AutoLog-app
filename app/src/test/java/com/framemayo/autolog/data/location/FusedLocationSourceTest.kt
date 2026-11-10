package com.framemayo.autolog.data.location

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FusedLocationSourceTest {
    private val fix = LocationFix(37.5, 127.0, 3f, 1L)
    private val fallback = object : LocationSource {
        override fun isAvailable() = true
        override suspend fun currentFix() = fix
    }

    @Test
    fun unsupportedEnvironmentFallsBack() = runBlocking {
        val source = FusedLocationSource(ApplicationProvider.getApplicationContext(), fallback)
        assertTrue(source.isAvailable())
        assertEquals(fix, source.currentFix())
    }
}
