package com.framemayo.autolog.vehicle

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * 임시 개발 대응(#115): Pleos 2.1 콘솔의 DDS 거리와 PERF_ODOMETER가 불일치한다.
 * 명시적으로 켠 debug 빌드만 에뮬레이터 내부 콘솔의 km 값을 읽는다. SDK와 병합하지 않는다.
 * 콘솔 장애는 기존 수집기 재시도로 전달하며 추정값/캐시값을 반환하지 않는다.
 * 벤더 이미지 수정 후 consoleOdometer 옵션을 끄고 이 대응을 제거한다.
 */
internal class ConsoleOdometerSource(
    private val endpoint: URL = URL("http://127.0.0.1:8088/vehicle/state"),
    private val pollIntervalMs: Long = 1_000L,
) {
    suspend fun currentKm(): Float = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1_500
            connection.readTimeout = 1_500
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("콘솔 누적거리 HTTP ${connection.responseCode}")
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            currentCoroutineContext().ensureActive()
            parseConsoleOdometer(payload)
        } finally {
            connection.disconnect()
        }
    }

    fun updates(): Flow<Float> = flow {
        while (true) {
            emit(currentKm())
            delay(pollIntervalMs)
        }
    }
}

internal fun parseConsoleOdometer(payload: String): Float {
    try {
        val odometer = JSONObject(payload).getJSONObject("odometer")
        if (odometer.get("bridge_ready") != true || odometer.get("emulator_active") != true) {
            throw IOException("콘솔 누적거리 연결이 준비되지 않았습니다")
        }
        val km = (odometer.get("traveled_distance_km") as? Number)?.toDouble()
            ?: throw IOException("콘솔 누적거리 숫자가 없습니다")
        if (!km.isFinite() || km < 0.0 || km > 1_000_000.0) throw IOException("콘솔 누적거리 범위를 벗어났습니다")
        return km.toFloat()
    } catch (error: JSONException) {
        throw IOException("콘솔 누적거리 응답 형식이 올바르지 않습니다", error)
    }
}
