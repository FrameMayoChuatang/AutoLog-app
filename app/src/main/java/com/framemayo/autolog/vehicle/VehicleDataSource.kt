package com.framemayo.autolog.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@SuppressLint("MissingPermission") // 권한은 Manifest에 선언 (pleos.car.permission.CAR_DRIVE)
class VehicleDataSource(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
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

    suspend fun currentOdometerKm(): Float? = suspendCancellableCoroutine { cont ->
        vehicle.getOdometer().getCurrentDistanceDriven(
            onComplete = { if (cont.isActive) cont.resume(it) },
            onFailed = { e ->
                Log.w(TAG, "getCurrentDistanceDriven failed", e)
                if (cont.isActive) cont.resume(null)
            },
        )
    }

    suspend fun vin(): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            vehicle.getCarInfo().getVINInfo(
                onComplete = { if (cont.isActive) cont.resume(it?.trim()?.uppercase()?.takeIf(VIN::matches)) },
                onFailed = { e ->
                    Log.w(TAG, "getVINInfo failed", e)
                    if (cont.isActive) cont.resume(null)
                },
            )
        }.onFailure {
            Log.w(TAG, "getVINInfo threw", it)
            if (cont.isActive) cont.resume(null)
        }
    }

    fun odometerKm(): Flow<Float?> = callbackFlow {
        val odometer = vehicle.getOdometer()
        val listener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                trySend(value)
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "registerDistanceDriven failed", e)
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
            }
        }
        gear.registerGearSelection(listener)
        awaitClose { gear.unregisterGearSelection(listener) }
    }

    companion object {
        private const val TAG = "VehicleDataSource"

        // 17자리, I·O·Q 제외 (에뮬레이터는 "\"invalid\""를 돌려줌)
        private val VIN = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    }
}
