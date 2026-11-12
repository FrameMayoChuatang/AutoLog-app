package com.framemayo.autolog.data.sync

import android.app.Application
import com.framemayo.autolog.data.remote.ServerApi
import com.framemayo.autolog.data.remote.TestServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HttpSyncApiTest {
    private var status = 200
    private var body = """{"serverVersion":3}"""
    private var method = ""
    private var path = ""
    private var auth: String? = null
    private var sent = ""
    private val server = TestServer { m, p, a, b ->
        method = m; path = p; auth = a; sent = b
        status to body
    }

    @After fun stop() = server.stop()

    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val completed = TripPayload(
        uuid = uuid, sourceRevision = 2, baseServerVersion = 1, driverId = "0a94e986-dfcf-4e4a-b490-f96876c62aed",
        startedAt = 1_700_000_000_123, endedAt = 1_700_000_600_000, lastObservedAt = 1_700_000_600_000,
        startOdometerKm = 10000.1f, endOdometerKm = 10004.25f, lastOdometerKm = 10004.25f,
        state = "COMPLETED", collectionIssues = null,
    )

    private fun api() = HttpSyncApi(ServerApi(server.url, token = { "DEVICE" }))

    @Test fun sendsContractFieldsOnly() = runBlocking {
        assertEquals(SyncResult.Ok(3), api().upload(completed))
        assertEquals("PUT", method)
        assertEquals("/api/device/trips/$uuid", path)
        assertEquals("Bearer DEVICE", auth)
        val json = JSONObject(sent)
        assertEquals(
            setOf("sourceRevision", "baseServerVersion", "driverId", "startedAt", "endedAt", "lastObservedAt",
                "startOdometerKm", "endOdometerKm", "lastOdometerKm", "state", "measurementSource"),
            json.keys().asSequence().toSet(),
        )
        assertEquals(2, json.getLong("sourceRevision"))
        assertEquals(1, json.getLong("baseServerVersion"))
        assertEquals("2023-11-14T22:13:20.123Z", json.getString("startedAt"))
        assertEquals("COMPLETED", json.getString("state"))
        assertEquals("PLEOS_ODOMETER", json.getString("measurementSource"))
    }

    @Test fun distancesAreKmWithAtMostThreeDecimals() = runBlocking {
        api().upload(completed)
        // Float 10000.1f를 Double로 바꾸면 10000.099609375처럼 길어져 서버가 거절함
        assertTrue(sent, sent.contains("\"startOdometerKm\":10000.100"))
        assertTrue(sent, sent.contains("\"endOdometerKm\":10004.250"))
    }

    @Test fun nullFieldsAreOmittedAndIssuesAreSent() = runBlocking {
        api().upload(completed.copy(driverId = null, endedAt = null, endOdometerKm = null, state = "INTERRUPTED", collectionIssues = listOf("SAMPLING_INTERRUPTED")))
        val json = JSONObject(sent)
        assertFalse(json.has("driverId"))
        assertFalse(json.has("endedAt"))
        assertFalse(json.has("endOdometerKm"))
        assertEquals("SAMPLING_INTERRUPTED", json.getJSONArray("collectionIssues").getString(0))
    }

    @Test fun statusCodesMapToResults() = runBlocking {
        val expected = mapOf(
            409 to SyncResult.Conflict, 401 to SyncResult.Unauthorized, 400 to SyncResult.Rejected, 404 to SyncResult.Rejected,
            403 to SyncResult.Retry, 429 to SyncResult.Retry, 500 to SyncResult.Retry, 503 to SyncResult.Retry,
        )
        for ((code, result) in expected) {
            status = code; body = """{"code":"X"}"""
            assertEquals("HTTP $code", result, api().upload(completed))
        }
    }

    @Test fun okWithoutServerVersionIsRetry() = runBlocking {
        body = "{}"
        assertEquals(SyncResult.Retry, api().upload(completed))
    }

    @Test fun offlineIsRetry() = runBlocking {
        val dead = TestServer { _, _, _, _ -> 200 to "{}" }
        val url = dead.url
        dead.stop()
        assertEquals(SyncResult.Retry, HttpSyncApi(ServerApi(url)).upload(completed))
    }

    @Test fun fetchParsesVersionRevisionAndLock() = runBlocking {
        body = """{"serverVersion":4,"sourceRevision":2,"measurementLockedAt":"2026-11-11T00:00:00Z"}"""
        assertEquals(FetchResult.Found(4, 2, true, false), api().fetch(uuid))
        assertEquals("GET", method)
        body = """{"serverVersion":4,"sourceRevision":2,"measurementLockedAt":null}"""
        assertEquals(FetchResult.Found(4, 2, false, false), api().fetch(uuid))
        body = """{"serverVersion":4,"sourceRevision":2}"""
        assertEquals(FetchResult.Found(4, 2, false, false), api().fetch(uuid))
        body = """{"serverVersion":5,"sourceRevision":2,"approvalStatus":"APPROVED"}"""
        assertEquals(FetchResult.Found(5, 2, false, true), api().fetch(uuid))
        body = """{"serverVersion":5,"sourceRevision":2,"approvalStatus":"PENDING"}"""
        assertEquals(FetchResult.Found(5, 2, false, false), api().fetch(uuid))
    }

    @Test fun fetchStatusCodes() = runBlocking {
        status = 404; assertEquals(FetchResult.NotFound, api().fetch(uuid))
        status = 401; assertEquals(FetchResult.Unauthorized, api().fetch(uuid))
        status = 500; assertEquals(FetchResult.Retry, api().fetch(uuid))
        status = 200; body = "{}"; assertEquals(FetchResult.Retry, api().fetch(uuid))
    }

    private val confirm = ConfirmPayload(
        uuid = uuid, expectedVersion = 3, driverId = "0a94e986-dfcf-4e4a-b490-f96876c62aed",
        classification = "BUSINESS", purposeText = "일반 업무", note = "계약 미팅", reason = "차량에서 운전자 확인",
    )

    @Test fun confirmSendsContractFieldsToConfirmPath() = runBlocking {
        body = """{"serverVersion":4}"""
        assertEquals(SyncResult.Ok(4), api().confirm(confirm))
        assertEquals("POST", method)
        assertEquals("/api/device/trips/$uuid/confirm", path)
        assertEquals("Bearer DEVICE", auth)
        val json = JSONObject(sent)
        assertEquals(
            setOf("expectedVersion", "driverId", "classification", "purposeText", "note", "reason"),
            json.keys().asSequence().toSet(),
        )
        assertEquals(3, json.getLong("expectedVersion"))
        assertEquals("BUSINESS", json.getString("classification"))
    }

    @Test fun confirmOmitsEmptyOptionalFields() = runBlocking {
        api().confirm(confirm.copy(classification = "PERSONAL", purposeText = null, note = null))
        val json = JSONObject(sent)
        assertFalse(json.has("purposeText"))
        assertFalse(json.has("note"))
    }

    @Test fun confirmStatusCodesMapToResults() = runBlocking {
        val expected = mapOf(
            409 to SyncResult.Conflict, 401 to SyncResult.Unauthorized, 400 to SyncResult.Rejected, 404 to SyncResult.Rejected,
            403 to SyncResult.Retry, 429 to SyncResult.Retry, 500 to SyncResult.Retry,
        )
        for ((code, result) in expected) {
            status = code; body = """{"code":"X"}"""
            assertEquals("HTTP $code", result, api().confirm(confirm))
        }
        status = 200; body = "{}"
        assertEquals(SyncResult.Retry, api().confirm(confirm))
    }
}
