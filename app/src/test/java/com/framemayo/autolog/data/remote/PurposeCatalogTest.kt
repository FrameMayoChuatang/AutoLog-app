package com.framemayo.autolog.data.remote

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PurposeCatalogTest {
    private var code = 200
    private var response = "[]"
    private var path = ""
    private var auth: String? = null
    private val server = TestServer { _, p, a, _ -> path = p; auth = a; code to response }
    private val catalog = PurposeCatalog(ServerApi(server.url, token = { "DEVICE" }))
    @After fun close() = server.stop()

    @Test fun activeCatalogIsReadUsingDeviceCredentials() = runBlocking {
        response = """[{"id":"1","name":"거래처 방문","active":true},{"id":"2","name":"삭제 항목","active":false}]"""
        assertEquals(listOf("거래처 방문"), catalog.load()!!.map { it.name })
        assertEquals("/api/device/catalog/purposes", path)
        assertEquals("Bearer DEVICE", auth)
    }

    @Test fun emptyCatalogAndFailedResponseAreDistinct() = runBlocking {
        assertEquals(emptyList<Any>(), catalog.load())
        response = """[{"id":"1"}]"""
        assertNull(catalog.load())
        code = 503; response = "{}"
        assertNull(catalog.load())
    }
}
