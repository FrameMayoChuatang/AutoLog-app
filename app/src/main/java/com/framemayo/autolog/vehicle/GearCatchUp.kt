package com.framemayo.autolog.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun VehicleGear?.isUnknown(): Boolean = this == null || this == VehicleGear.GEAR_UNKNOWN

/**
 * 기어 리스너 이벤트에 현재값 조회를 더한다(#13).
 *
 * Pleos SDK 기어 리스너는 값이 바뀔 때만 알린다. 부팅 직후처럼 등록 시 초기값이 오지 않으면, 이후 기본값과
 * 같은 기어(예: P)를 설정해도 이벤트가 없어 앱은 재시작 전까지 기어를 모른다. 그래서 기어를 모르는 동안
 * [pollMs]마다 [read]로 현재값을 조회해 채운다. 알게 된 뒤에는 조회하지 않는다.
 *
 * 리스너 값이 항상 우선한다. 조회 결과는 그 사이 리스너가 값을 주지 않았을 때만 내보낸다(오래된 값으로 덮지 않음).
 * 업스트림 예외는 그대로 전파해 재구독([resubscribing])에 맡긴다.
 */
fun gearWithCatchUp(
    events: Flow<VehicleGear?>,
    read: suspend () -> VehicleGear?,
    pollMs: Long = GEAR_POLL_MS,
    delayFn: suspend (Long) -> Unit = { delay(it) },
): Flow<VehicleGear?> = channelFlow {
    val lock = Mutex()
    var known: VehicleGear? = null
    val poller = launch {
        while (true) {
            if (lock.withLock { known.isUnknown() }) {
                val value = try {
                    read()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                lock.withLock {
                    if (known.isUnknown() && !value.isUnknown()) {
                        known = value
                        send(value)
                    }
                }
            }
            delayFn(pollMs)
        }
    }
    events.collect { gear ->
        lock.withLock {
            known = gear
            send(gear)
        }
    }
    poller.cancel()
}

const val GEAR_POLL_MS = 2_000L
