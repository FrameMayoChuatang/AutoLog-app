package com.framemayo.autolog.data.link

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VehicleLinkRepositoryTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", Context.MODE_PRIVATE)
    private val api = FakeVehicleLinkApi()
    private fun repo() = VehicleLinkRepository(prefs, api, clock = { 42 })

    @Test
    fun connectSavesAndSurvivesRestart() = runBlocking {
        val r = repo().connect(" 123456 ", "12가 3456", "KMH0000")
        assertTrue(r is LinkResult.Ok)
        assertEquals(VehicleLink(1, "테스트 회사", "12가3456", "KMH0000", 42), repo().link.value)
    }

    @Test
    fun invalidInputsAndOfflineSaveNothing() = runBlocking {
        val repo = repo()
        assertEquals(LinkResult.InvalidNumber, repo.connect("123456", "abc", null))
        assertEquals(LinkResult.InvalidCode, repo.connect("12", "12가3456", null))
        api.online = false
        assertEquals(LinkResult.Retry, repo.connect("123456", "12가3456", null))
        assertNull(repo.link.value)
        assertNull(repo().link.value)
    }

    @Test
    fun disconnectClears() = runBlocking {
        val repo = repo()
        repo.connect("123456", "123가4567", null)
        repo.disconnect()
        assertNull(repo.link.value)
        assertNull(repo().link.value)
    }
}
