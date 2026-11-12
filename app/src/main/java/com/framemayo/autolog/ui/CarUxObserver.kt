package com.framemayo.autolog.ui

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log

internal enum class DrivingUxFailure {
    WAITING, DISCONNECTED, READ_FAILED, API_UNAVAILABLE,
}

/** 제한 상태를 받기 전이나 연결이 끊긴 동안에는 입력을 허용하지 않는다. */
internal data class DrivingUxState(
    val ready: Boolean = false,
    val requiresOptimization: Boolean = true,
    val activeRestrictions: Int = 0,
    val maxStringLength: Int = 20,
    val failure: DrivingUxFailure = DrivingUxFailure.WAITING,
) {
    val allowsInput: Boolean get() = ready && !requiresOptimization && activeRestrictions == 0
}

/** Activity가 보이는 동안만 플랫폼 제한을 구독한다. 차량 기록 서비스와는 독립적이다. */
internal class CarUxObserver(private val context: Context, private val onChanged: (DrivingUxState) -> Unit) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var generation = 0

    fun start() {
        stop()
        val session = generation
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (session != generation) return@createCar
                onChanged(DrivingUxState(failure = DrivingUxFailure.DISCONNECTED))
                manager = null
                if (ready) {
                    try {
                        val restrictions = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        manager = restrictions
                        restrictions.registerListener { value ->
                            if (session == generation && manager === restrictions) publish(value)
                        }
                        publish(restrictions.currentCarUxRestrictions)
                    } catch (error: RuntimeException) {
                        onChanged(DrivingUxState(failure = DrivingUxFailure.READ_FAILED))
                        Log.w(TAG, "주행 제한 상태를 읽을 수 없어 입력을 잠급니다", error)
                    } catch (error: LinkageError) {
                        onChanged(DrivingUxState(failure = DrivingUxFailure.API_UNAVAILABLE))
                        Log.w(TAG, "차량 제한 API를 사용할 수 없습니다", error)
                    }
                }
            }
        } catch (error: RuntimeException) {
            onChanged(DrivingUxState(failure = DrivingUxFailure.DISCONNECTED))
            Log.w(TAG, "차량 제한 서비스 연결 실패", error)
        } catch (error: LinkageError) {
            onChanged(DrivingUxState(failure = DrivingUxFailure.API_UNAVAILABLE))
            // 표준 API가 없는 이미지에서도 운행 현황은 표시하되 입력은 잠근다.
            Log.w(TAG, "차량 제한 API를 사용할 수 없습니다", error)
        }
    }

    private fun publish(value: CarUxRestrictions?) {
        onChanged(value?.let {
            DrivingUxState(true, it.isRequiresDistractionOptimization, it.activeRestrictions, it.maxRestrictedStringLength)
        } ?: DrivingUxState(failure = DrivingUxFailure.READ_FAILED))
    }

    fun stop() {
        generation++
        onChanged(DrivingUxState())
        try {
            manager?.unregisterListener()
        } catch (error: RuntimeException) {
            Log.w(TAG, "주행 제한 구독 해제 실패", error)
        } catch (error: LinkageError) {
            Log.w(TAG, "주행 제한 구독 해제 API 미지원", error)
        } finally {
            manager = null
            try {
                car?.disconnect()
            } catch (error: RuntimeException) {
                Log.w(TAG, "차량 제한 연결 해제 실패", error)
            } catch (error: LinkageError) {
                Log.w(TAG, "차량 제한 연결 해제 API 미지원", error)
            }
            car = null
        }
    }

    private companion object { const val TAG = "AutoLogUx" }
}
