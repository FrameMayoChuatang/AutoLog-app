package com.framemayo.autolog.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlin.coroutines.cancellation.CancellationException

/** 차량 SDK 초기화·구독 재시도 간격. 1초부터 두 배씩, 최대 30초(#89). */
object Backoff {
    const val INITIAL_MS = 1_000L
    const val MAX_MS = 30_000L

    fun next(currentMs: Long, maxMs: Long = MAX_MS): Long = (currentMs * 2).coerceAtMost(maxMs)
}

/**
 * [block]이 성공할 때까지 지수 백오프로 무한 재시도한다. 취소는 삼키지 않고 그대로 던진다.
 * [onRetry]는 실패 직후, 대기 전에 (1부터 세는 실패 횟수, 원인, 이번 대기 ms)로 불린다.
 */
suspend fun <T> retryWithBackoff(
    initialMs: Long = Backoff.INITIAL_MS,
    maxMs: Long = Backoff.MAX_MS,
    delayFn: suspend (Long) -> Unit = { delay(it) },
    onRetry: (attempt: Int, error: Throwable, delayMs: Long) -> Unit = { _, _, _ -> },
    block: suspend () -> T,
): T {
    var waitMs = initialMs
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onRetry(++attempt, e, waitMs)
            delayFn(waitMs)
            waitMs = Backoff.next(waitMs, maxMs)
        }
    }
}

/**
 * 업스트림이 예외로 닫히면 백오프 뒤 다시 구독한다. 값을 하나라도 받으면 백오프를 초기화한다.
 * 업스트림이 예외 없이 끝나면 이 흐름도 끝난다. 다운스트림 예외와 취소는 그대로 전파한다.
 *
 * - [onFailure]: 구독 실패 직후(대기 전) 호출. 서비스는 [CollectorStatus.RETRYING]으로 바꾼다.
 * - [prime]: 재구독 직전 한 번 호출해 null이 아니면 먼저 내보낸다(누적거리 1회 조회로 보정).
 *   백오프 초기화에는 쓰지 않는다(구독이 계속 실패하는데 조회만 되면 간격이 줄지 않게).
 * - [onRecovered]: 실패 뒤 구독에서 첫 값을 받았을 때 호출. 서비스는 RUNNING으로 되돌린다.
 */
fun <T> Flow<T>.resubscribing(
    initialMs: Long = Backoff.INITIAL_MS,
    maxMs: Long = Backoff.MAX_MS,
    delayFn: suspend (Long) -> Unit = { delay(it) },
    onFailure: (attempt: Int, error: Throwable, delayMs: Long) -> Unit = { _, _, _ -> },
    prime: (suspend () -> T?)? = null,
    onRecovered: () -> Unit = {},
): Flow<T> = flow {
    var waitMs = initialMs
    var attempt = 0
    while (true) {
        var failure: Throwable? = null
        this@resubscribing
            .onEach {
                if (attempt > 0) onRecovered()
                attempt = 0
                waitMs = initialMs
            }
            .catch { if (it is CancellationException) throw it else failure = it }
            .collect { emit(it) }
        val error = failure ?: return@flow
        onFailure(++attempt, error, waitMs)
        delayFn(waitMs)
        waitMs = Backoff.next(waitMs, maxMs)
        val primed = try {
            prime?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null // 1회 조회 실패는 재구독을 막지 않는다.
        }
        primed?.let { emit(it) }
    }
}

/**
 * 수집 루프의 한 주기를 감싸 예외가 루프·서비스를 끝내지 않게 한다(#139).
 * 예외는 [onError]로 알리고 null을 돌려 다음 주기에 계속한다. 실패 뒤 처음 성공하면 [onRecovered]를 한 번 부른다.
 * 취소([CancellationException])는 삼키지 않는다. 한 루프에 하나씩 만들어 쓴다.
 */
class CycleGuard(
    private val onError: (Exception) -> Unit,
    private val onRecovered: () -> Unit = {},
) {
    @Volatile private var failing = false

    suspend fun <T> run(block: suspend () -> T): T? = try {
        block().also {
            if (failing) {
                failing = false
                onRecovered()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failing = true
        onError(e)
        null
    }
}

/**
 * 여러 수집 경로의 실패를 모아 하나의 수집 상태를 만든다(PR #174 리뷰).
 * 수집 상태는 화면의 입력 잠금(`canUseParkedControls`)에 쓰이므로, 차량 상태를 판단하는 경로(SDK 초기화·누적거리·기어)만 등록한다.
 * 한 경로가 회복해도 다른 경로가 실패 중이면 [CollectorStatus.RETRYING]을 유지한다.
 */
class CollectorHealth {
    private val failing = mutableSetOf<String>()

    @Synchronized
    fun report(source: String, ok: Boolean): CollectorStatus {
        if (ok) failing.remove(source) else failing.add(source)
        return if (failing.isEmpty()) CollectorStatus.RUNNING else CollectorStatus.RETRYING
    }
}
