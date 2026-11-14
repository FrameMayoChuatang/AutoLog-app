package com.framemayo.autolog.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.framemayo.autolog.R
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@SuppressLint("MissingPermission") // 권한은 Manifest에 선언 (pleos.car.permission.CAR_DRIVE)
class VehicleDataSource(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val consoleOdometer =
        if (context.resources.getBoolean(R.bool.use_console_odometer)) ConsoleOdometerSource() else null
    @Volatile private var initialized = false

    fun initialize() {
        if (initialized) return
        vehicle.initialize()
        initialized = true
    }

    fun release() {
        if (!initialized) return
        vehicle.release()
        initialized = false
    }

    suspend fun currentOdometerKm(): Float? {
        consoleOdometer?.let { source ->
            return try {
                source.currentKm()
            } catch (error: IOException) {
                Log.w(TAG, "개발용 콘솔 누적거리 조회 실패", error)
                null // 초기 복구는 기존 SDK 조회 실패와 동일하게 다음 유효값을 기다린다.
            }
        }
        return currentSdkOdometerKm()
    }

    private suspend fun currentSdkOdometerKm(): Float? = suspendCancellableCoroutine { cont ->
        vehicle.getOdometer().getCurrentDistanceDriven(
            onComplete = { if (cont.isActive) cont.resume(it) },
            onFailed = { e ->
                Log.w(TAG, "getCurrentDistanceDriven failed", e)
                if (cont.isActive) cont.resume(null)
            },
        )
    }

    fun odometerKm(): Flow<Float?> = consoleOdometer?.updates() ?: sdkOdometerKm()

    private fun sdkOdometerKm(): Flow<Float?> = callbackFlow {
        val odometer = vehicle.getOdometer()
        val listener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                trySend(value)
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "registerDistanceDriven failed", e)
                close(e) // 수집기가 백오프 후 재구독한다.
            }
        }
        odometer.registerDistanceDriven(listener)
        awaitClose { odometer.unregisterDistanceDriven(listener) }
    }

    @Suppress("DEPRECATION")
    fun gear(): Flow<VehicleGear?> = callbackFlow {
        val gear = vehicle.getGear()
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                trySend(gear)
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "registerGearSelection failed", e)
                close(e)
            }
        }
        gear.registerGearSelection(listener)
        awaitClose { gear.unregisterGearSelection(listener) }
    }

    /**
     * 현재 기어를 한 번 조회한다. 실패하면 null.
     * 리스너는 값이 "바뀔 때"만 오므로, 부팅 직후 기본값(P)과 같은 값을 설정하면 이벤트가 오지 않는다(#13).
     */
    suspend fun currentGear(): VehicleGear? = suspendCancellableCoroutine { cont ->
        vehicle.getGear().getCurrentGearSelectionState(
            { value: VehicleGear? -> if (cont.isActive) cont.resume(value) },
            { e: Exception ->
                Log.w(TAG, "getCurrentGearSelectionState failed", e)
                if (cont.isActive) cont.resume(null)
            },
        )
    }

    /** 연료량(%). 내연기관이 아니거나 미지원이면 실패로 닫힌다. */
    fun fuelPercent(): Flow<Float?> = callbackFlow {
        val fuel = vehicle.getFuel()
        val listener = object : FuelPercentListener {
            override fun onFuelPercentUpdated(value: Int?) {
                trySend(value?.toFloat())
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "registerFuelPercent failed", e)
                close(e)
            }
        }
        fuel.registerFuelPercent(listener)
        awaitClose { fuel.unregisterFuelPercent(listener) }
    }

    /** EV 배터리 잔량(SoC, %). 전기차가 아니거나 미지원이면 실패로 닫힌다. */
    fun batteryPercent(): Flow<Float?> = callbackFlow {
        val battery = vehicle.getEvBattery()
        val listener = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                trySend(value?.toFloat())
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "registerEvBatteryStateOfCharge failed", e)
                close(e)
            }
        }
        battery.registerEvBatteryStateOfCharge(listener)
        awaitClose { battery.unregisterEvBatteryStateOfCharge(listener) }
    }

    fun chargePortOpen(): Flow<Boolean?> = callbackFlow {
        val battery = vehicle.getEvBattery()
        val listener = object : EvChargePortOpenListener {
            override fun onEvChargePortOpenUpdated(value: Boolean?) {
                trySend(value)
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "registerEvChargePortOpen failed", e)
                close(e)
            }
        }
        battery.registerEvChargePortOpen(listener)
        awaitClose { battery.unregisterEvChargePortOpen(listener) }
    }

    /** EV 배터리 용량(kWh). SDK의 Wh 값을 변환한다. 미지원이거나 비정상 값이면 null. */
    suspend fun batteryCapacityKwh(): Float? = suspendCancellableCoroutine { cont ->
        vehicle.getEvBattery().getEvBatteryCapacity(
            // SDK 값은 Wh다. kWh로 바꿔 넘긴다(#101).
            { value: Float? -> if (cont.isActive) cont.resume(sdkBatteryCapacityToKwh(value)) },
            { e: Exception ->
                Log.w(TAG, "getEvBatteryCapacity failed", e)
                if (cont.isActive) cont.resume(null)
            },
        )
    }

    companion object {
        private const val TAG = "VehicleDataSource"
    }
}
