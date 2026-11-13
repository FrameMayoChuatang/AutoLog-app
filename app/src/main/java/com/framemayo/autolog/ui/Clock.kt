package com.framemayo.autolog.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * [intervalMs]마다 갱신되는 현재 시각. 진행 중 운행 시간을 DB 저장 주기와 무관하게 흘러가게 보여 준다(#147).
 * 이 값을 읽는 컴포저블만 다시 그려진다.
 */
@Composable
fun rememberNow(intervalMs: Long = 10_000L): State<Long> = produceState(System.currentTimeMillis(), intervalMs) {
    while (true) {
        value = System.currentTimeMillis()
        delay(intervalMs)
    }
}
