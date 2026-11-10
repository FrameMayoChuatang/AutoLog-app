package com.framemayo.autolog.data.link

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.framemayo.autolog.data.remote.ServerApi
import com.framemayo.autolog.data.remote.TestServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val PAIR_OK = """{"accessToken":"DEVICE_TOKEN","tokenType":"Bearer","expiresAt":"2027-02-01T00:00:00Z",
"vehicle":{"id":"7a1c0f56-0000-4000-8000-000000000001","companyId":"c","registrationNumber":"12가3456","model":"아이오닉 5","active":true,"createdAt":"2026-11-10T00:00:00Z"}}"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VehicleLinkRepositoryTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("t", Context.MODE_PRIVATE)
    private var status = 200
    private var body = PAIR_OK
    private var sentBody = ""
    private val server = TestServer { _, path, _, req ->
        sentBody = req
        check(path == "/api/device/pair")
        status to body
    }

    @After fun stop() = server.stop()

    private fun repo(url: String = server.url) = VehicleLinkRepository(prefs, HttpVehicleLinkApi(ServerApi(url)), clock = { 42 })

    @Test
    fun pairSavesLinkAndSurvivesRestart() = runBlocking {
        val r = repo().connect("  PAIR-TOKEN ")
        assertTrue(r is LinkResult.Ok)
        assertEquals("""{"pairingToken":"PAIR-TOKEN"}""", sentBody)
        val link = repo().link.value!!
        assertEquals("DEVICE_TOKEN", link.accessToken)
        assertEquals("12가3456", link.registrationNumber)
        assertEquals("아이오닉 5", link.model)
        assertEquals(42, link.connectedAt)
    }

    @Test
    fun rejectedTokenIsInvalidCodeAndSavesNothing() = runBlocking {
        status = 401
        body = """{"code":"UNAUTHORIZED","message":"x"}"""
        assertEquals(LinkResult.InvalidCode, repo().connect("bad"))
        assertEquals(LinkResult.InvalidCode, repo().connect("   "))
        assertNull(repo().link.value)
    }

    @Test
    fun serverErrorOrBrokenBodyOrOfflineIsRetry() = runBlocking {
        status = 500; body = "{}"
        assertEquals(LinkResult.Retry, repo().connect("t"))
        status = 200; body = """{"accessToken":"x"}""" // vehicle 없음
        assertEquals(LinkResult.Retry, repo().connect("t"))
        val dead = TestServer { _, _, _, _ -> 200 to "{}" }
        val url = dead.url
        dead.stop()
        assertEquals(LinkResult.Retry, repo(url).connect("t"))
        assertNull(repo().link.value)
    }

    @Test
    fun disconnectClears() = runBlocking {
        val repo = repo()
        repo.connect("t")
        repo.disconnect()
        assertNull(repo.link.value)
        assertNull(repo().link.value)
    }

    @Test
    fun oldFormatPrefsAreIgnored() {
        prefs.edit().putLong("vehicleRemoteId", 1).putString("companyName", "테스트 회사").commit()
        assertNull(repo().link.value)
    }
}
