package com.framemayo.autolog.data.driver

import android.app.Application
import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.remote.ServerApi
import com.framemayo.autolog.data.remote.TestServer
import com.framemayo.autolog.data.repository.RemoteDriver
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HttpDriverApiTest {
    private var status = 200
    private var body = "[]"
    private var method = ""
    private var path = ""
    private var auth: String? = null
    private var sent = ""
    private val server = TestServer { m, p, a, b ->
        method = m; path = p; auth = a; sent = b
        status to body
    }

    @After fun stop() = server.stop()

    private fun api() = HttpDriverApi(ServerApi(server.url, token = { "DEVICE" }))
    private val id = "0a94e986-dfcf-4e4a-b490-f96876c62aed"
    private fun driverJson(status: String = "ACTIVE") =
        """{"id":"$id","companyId":"c","name":"Kim","department":"영업","status":"$status","locationConsent":true,"createdAt":"2026-11-11T00:00:00Z"}"""

    @Test fun listParsesDriversAndKeepsUnknownStatusAsPending() = runBlocking {
        val third = "33333333-3333-4333-8333-333333333333"
        body = "[${driverJson()},${driverJson("PENDING").replace(id, "22222222-2222-4222-8222-222222222222")},${driverJson("SUSPENDED").replace(id, third)}]"
        val r = api().list() as ApiOutcome.Ok
        assertEquals("GET", method)
        assertEquals("/api/device/drivers", path)
        assertEquals("Bearer DEVICE", auth)
        assertEquals(listOf(DriverStatus.ACTIVE, DriverStatus.PENDING, DriverStatus.PENDING), r.value.map { it.status })
        assertEquals(third, r.value.last().remoteId)
        assertEquals(RemoteDriver(id, "Kim", "영업", DriverStatus.ACTIVE), r.value.first())
    }

    @Test fun listWithUnreadableDriverIsRetriedInsteadOfShrunk() = runBlocking {
        body = """[${driverJson()},{"name":"아이디 없음","status":"ACTIVE"}]"""
        assertEquals(ApiOutcome.Retry, api().list())
    }

    @Test fun registerSendsNameAndDepartmentAndParsesCreated() = runBlocking {
        status = 201; body = driverJson("PENDING")
        val r = api().register("Kim", "영업") as ApiOutcome.Ok
        assertEquals("POST", method)
        assertEquals("/api/device/drivers", path)
        assertEquals(setOf("name", "department"), JSONObject(sent).keys().asSequence().toSet())
        assertEquals(DriverStatus.PENDING, r.value.status)
    }

    @Test fun consentSendsGrantedAndNoticeVersionToDriverPath() = runBlocking {
        body = driverJson()
        assertEquals(ApiOutcome.Ok(Unit), api().sendConsent(id, false, "v1"))
        assertEquals("/api/device/drivers/$id/consent", path)
        val json = JSONObject(sent)
        assertEquals(false, json.getBoolean("granted"))
        assertEquals("v1", json.getString("noticeVersion"))
    }

    @Test fun statusCodesMapToOutcomes() = runBlocking {
        val expected = mapOf(
            401 to ApiOutcome.Unauthorized, 400 to ApiOutcome.Rejected, 404 to ApiOutcome.Rejected, 409 to ApiOutcome.Rejected,
            403 to ApiOutcome.Retry, 429 to ApiOutcome.Retry, 500 to ApiOutcome.Retry,
        )
        for ((code, outcome) in expected) {
            status = code; body = """{"code":"X"}"""
            assertEquals("list $code", outcome, api().list())
            assertEquals("register $code", outcome, api().register("a", "b"))
            assertEquals("consent $code", outcome, api().sendConsent(id, true, "v1"))
        }
    }

    @Test fun malformedSuccessBodiesAreRetry() = runBlocking {
        status = 200; body = "{}"
        assertEquals(ApiOutcome.Retry, api().list())
        status = 201; body = "{}"
        assertEquals(ApiOutcome.Retry, api().register("a", "b"))
    }

    @Test fun offlineIsRetry() = runBlocking {
        val dead = TestServer { _, _, _, _ -> 200 to "[]" }
        val url = dead.url
        dead.stop()
        assertEquals(ApiOutcome.Retry, HttpDriverApi(ServerApi(url)).list())
    }
}
