package com.framemayo.autolog.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface ApiResult {
    data class Http(
        val code: Int,
        val json: JSONObject?,
        val retryAfterSeconds: Int? = null,
        val array: JSONArray? = null,
    ) : ApiResult {
        val ok: Boolean get() = code in 200..299
    }

    data object Offline : ApiResult
}

class ServerApi(
    private val baseUrl: String,
    private val token: () -> String? = { null },
    private val onUnauthorized: () -> Unit = {},
) {
    suspend fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): ApiResult =
        withContext(Dispatchers.IO) {
            val bearer = if (auth) token() else null
            val conn = try {
                (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                    bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(body.toString().toByteArray()) }
                    }
                }
            } catch (_: IOException) {
                return@withContext ApiResult.Offline
            }
            try {
                val code = conn.responseCode
                val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() }
                if (code == 401 && bearer != null) onUnauthorized()
                ApiResult.Http(
                    code,
                    text?.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() },
                    conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull(),
                    text?.takeIf { it.startsWith("[") }?.let { runCatching { JSONArray(it) }.getOrNull() },
                )
            } catch (_: IOException) {
                ApiResult.Offline
            } finally {
                conn.disconnect()
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
