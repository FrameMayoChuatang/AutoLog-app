package com.framemayo.autolog.vehicle

/**
 * SDK 배터리 용량(`INFO_EV_BATTERY_CAPACITY`)을 kWh로 바꾼다(#101).
 *
 * Pleos SDK는 이 값을 Wh로 준다(에뮬레이터 77400.0 = 가상 콘솔 77.4 kWh). 그대로 kWh로 쓰면 충전량·금액이 1000배가 된다.
 * 차량에 따라 kWh로 줄 가능성에 대비해 [WH_THRESHOLD]보다 크면 Wh로 보고 변환한다(승용 전기차 배터리는 1000 kWh를 넘지 않는다).
 * 변환 결과가 배터리 용량으로 말이 안 되면 null을 돌려 설정 기본값을 쓰게 한다.
 */
fun sdkBatteryCapacityToKwh(raw: Float?): Float? {
    if (raw == null || !raw.isFinite() || raw <= 0f) return null
    val kwh = if (raw > WH_THRESHOLD) raw / 1000f else raw
    return kwh.takeIf { it in MIN_CAPACITY_KWH..MAX_CAPACITY_KWH }
}

private const val WH_THRESHOLD = 1_000f
private const val MIN_CAPACITY_KWH = 1f
private const val MAX_CAPACITY_KWH = 300f
