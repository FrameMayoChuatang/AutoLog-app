package com.framemayo.autolog.vehicle

/**
 * 실시간 누적거리 수신값을 거르는 상태 필터(#141). [OdometerValidator]는 직전 값보다 작은 값만 막아서,
 * 한 번 튄 큰 값(예: 실제 1,270 km에 99,999 km)이 통과하면 그 값이 영구 기준이 되어 이후 정상 값이 모두 거부됐다.
 *
 * - 0·음수·NaN·무한대는 거부한다.
 * - 증가량이 [maxSpeedKmh] × 경과 시간 + [jumpSlackKm]를 넘으면 튀는 값으로 보고 거부한다.
 *   시연·Virtual Console은 누적거리를 한 번에 수십 km씩 올리므로 여유분을 크게 둔다.
 * - 기준보다 작은 값은 거부하되, 서로 그럴듯하게 이어지는(같거나 조금씩 증가하는) 작은 값이
 *   [lowConfirmCount]번 연속 오면 기준이 잘못된 것으로 보고 그 값으로 기준을 낮춘다(이상값 복구).
 *   재시작 직후 한 번 늦게 오는 이전 값은 연속되지 않으므로 계속 거부된다.
 *
 * 스레드 안전하지 않다. 한 수집 코루틴에서만 호출한다.
 */
class OdometerFilter(
    private val maxSpeedKmh: Float = MAX_SPEED_KMH,
    private val jumpSlackKm: Float = JUMP_SLACK_KM,
    private val lowConfirmCount: Int = LOW_CONFIRM_COUNT,
) {
    private var baselineKm: Float? = null
    private var baselineAt: Long = 0L

    private var lowKm: Float? = null
    private var lowAt: Long = 0L
    private var lowCount = 0

    val current: Float? get() = baselineKm

    /** 복구 때 한 번 읽은 실측값처럼 신뢰하는 값으로 기준을 맞춘다. */
    fun seed(km: Float?, at: Long) {
        if (!isUsable(km)) return
        baselineKm = km
        baselineAt = at
        resetLow()
    }

    /** 받아들이면 그 값을, 거부하면 null을 돌려준다. */
    fun accept(km: Float?, at: Long): Float? {
        if (!isUsable(km)) return null
        val value = km!!
        val base = baselineKm
        if (base == null) {
            seed(value, at)
            return value
        }
        if (value >= base) {
            if (value - base > allowedIncrease(base = baselineAt, at = at)) return null
            seed(value, at)
            return value
        }
        return acceptLow(value, at)
    }

    private fun acceptLow(value: Float, at: Long): Float? {
        val prev = lowKm
        val continues = prev != null && value >= prev && value - prev <= allowedIncrease(lowAt, at)
        lowCount = if (continues) lowCount + 1 else 1
        lowKm = value
        lowAt = at
        if (lowCount < lowConfirmCount) return null
        seed(value, at)
        return value
    }

    private fun allowedIncrease(base: Long, at: Long): Float {
        val hours = (at - base).coerceAtLeast(0L) / 3_600_000f
        return maxSpeedKmh * hours + jumpSlackKm
    }

    private fun resetLow() {
        lowKm = null
        lowCount = 0
    }

    private fun isUsable(km: Float?) = km != null && km > 0f && km.isFinite()

    companion object {
        const val MAX_SPEED_KMH = 300f
        const val JUMP_SLACK_KM = 100f
        const val LOW_CONFIRM_COUNT = 3
    }
}
