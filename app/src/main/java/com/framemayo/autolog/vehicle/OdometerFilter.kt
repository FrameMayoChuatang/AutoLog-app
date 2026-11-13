package com.framemayo.autolog.vehicle

/**
 * 실시간 누적거리 수신값을 거르는 상태 필터(#141). [OdometerValidator]는 직전 값보다 작은 값만 막아서,
 * 한 번 튄 큰 값(예: 실제 1,270 km에 99,999 km)이 통과하면 그 값이 영구 기준이 되어 이후 정상 값이 모두 거부됐다.
 *
 * - 0·음수·NaN·무한대는 거부한다.
 * - 증가량이 [maxSpeedKmh] × 경과 시간 + [jumpSlackKm]를 넘으면 튀는 값으로 보고 거부한다.
 *   시연·Virtual Console은 누적거리를 한 번에 수십 km씩 올리므로 여유분을 크게 둔다.
 * - 기준보다 작은 값도 거부한다.
 * - 거부된 값이 서로 그럴듯하게 이어지며(같거나 조금씩 증가) [confirmCount]번 연속 오면 기준이 틀렸거나
 *   실제로 크게 바뀐 것으로 보고 그 값으로 기준을 옮긴다(위·아래 모두 복구). 튄 값 하나 뒤에 정상 값이
 *   오면 그 정상 값은 바로 통과하고 튄 값은 버려진다. 재시작 직후 한 번 늦게 오는 이전 값도 연속되지 않아 거부된다.
 *
 * 스레드 안전하지 않다. 한 수집 코루틴에서만 호출한다.
 */
class OdometerFilter(
    private val maxSpeedKmh: Float = MAX_SPEED_KMH,
    private val jumpSlackKm: Float = JUMP_SLACK_KM,
    private val confirmCount: Int = CONFIRM_COUNT,
) {
    private var baselineKm: Float? = null
    private var baselineAt: Long = 0L

    /** 거부된 값들의 연속(기준 이동 후보). */
    private var candidateKm: Float? = null
    private var candidateAt: Long = 0L
    private var candidateCount = 0

    val current: Float? get() = baselineKm

    /** 복구 때 한 번 읽은 실측값처럼 신뢰하는 값으로 기준을 맞춘다. */
    fun seed(km: Float?, at: Long) {
        if (!isUsable(km)) return
        baselineKm = km
        baselineAt = at
        resetCandidate()
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
        if (value >= base && value - base <= allowedIncrease(baselineAt, at)) {
            seed(value, at)
            return value
        }
        return confirmCandidate(value, at)
    }

    private fun confirmCandidate(value: Float, at: Long): Float? {
        val prev = candidateKm
        val continues = prev != null && value >= prev && value - prev <= allowedIncrease(candidateAt, at)
        candidateCount = if (continues) candidateCount + 1 else 1
        candidateKm = value
        candidateAt = at
        if (candidateCount < confirmCount) return null
        seed(value, at)
        return value
    }

    private fun allowedIncrease(base: Long, at: Long): Float {
        val hours = (at - base).coerceAtLeast(0L) / 3_600_000f
        return maxSpeedKmh * hours + jumpSlackKm
    }

    private fun resetCandidate() {
        candidateKm = null
        candidateCount = 0
    }

    private fun isUsable(km: Float?) = km != null && km > 0f && km.isFinite()

    companion object {
        const val MAX_SPEED_KMH = 300f
        const val JUMP_SLACK_KM = 100f
        const val CONFIRM_COUNT = 3
    }
}
