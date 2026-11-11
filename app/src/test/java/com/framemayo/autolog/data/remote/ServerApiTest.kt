package com.framemayo.autolog.data.remote

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServerApiTest {
    private var lastAuth: String? = null
    private var status = 200
    private val server = TestServer { _, _, auth, _ ->
        lastAuth = auth
        status to if (status == 204) "" else """{"ok":true}"""
    }
    private var unauthorized = 0

    @After fun stop() = server.stop()

    private fun api(token: String? = "T1") = ServerApi(server.url, { token }, { unauthorized++ })

    @Test
    fun sendsBearerTokenAndParsesJson() = runBlocking {
        val r = api().call("GET", "/api/device/vehicle") as ApiResult.Http
        assertTrue(r.ok)
        assertEquals(true, r.json?.getBoolean("ok"))
        assertEquals("Bearer T1", lastAuth)
    }

    @Test
    fun noAuthHeaderWhenDisabledOrNoToken() = runBlocking {
        api().call("POST", "/api/device/pair", JSONObject().put("a", 1), auth = false)
        assertNull(lastAuth)
        api(token = null).call("GET", "/x")
        assertNull(lastAuth)
    }

    @Test
    fun unauthorizedWithTokenTriggersCallbackOnce() = runBlocking {
        status = 401
        val r = api().call("GET", "/x") as ApiResult.Http
        assertEquals(401, r.code)
        assertEquals(1, unauthorized)
    }

    @Test
    fun unauthorizedWithoutTokenDoesNotTriggerCallback() = runBlocking {
        status = 401
        api().call("POST", "/api/device/pair", auth = false)
        api(token = null).call("GET", "/x")
        assertEquals(0, unauthorized)
    }

    @Test
    fun parsesRetryAfterSeconds() = runBlocking {
        status = 429
        server.responseHeaders["Retry-After"] = "30"
        assertEquals(30, (api().call("POST", "/x", auth = false) as ApiResult.Http).retryAfterSeconds)
        server.responseHeaders.clear()
        assertNull((api().call("POST", "/x", auth = false) as ApiResult.Http).retryAfterSeconds)
    }

    @Test
    fun parsesJsonArrayBody() = runBlocking {
        val arrayServer = TestServer { _, _, _, _ -> 200 to """[{"id":"a"},{"id":"b"}]""" }
        val r = ServerApi(arrayServer.url).call("GET", "/x") as ApiResult.Http
        arrayServer.stop()
        assertNull(r.json)
        assertEquals(2, r.array?.length())
    }

    @Test
    fun emptyBodyIsOk() = runBlocking {
        status = 204
        val r = api().call("POST", "/api/auth/logout") as ApiResult.Http
        assertTrue(r.ok)
        assertNull(r.json)
    }

    @Test
    fun unreachableServerIsOffline() = runBlocking {
        val dead = TestServer { _, _, _, _ -> 200 to "{}" }
        val url = dead.url
        dead.stop()
        assertEquals(ApiResult.Offline, ServerApi(url).call("GET", "/x"))
        assertFalse(unauthorized > 0)
    }
}
