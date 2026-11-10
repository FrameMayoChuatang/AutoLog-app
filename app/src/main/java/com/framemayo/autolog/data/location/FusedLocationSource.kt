package com.framemayo.autolog.data.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
class FusedLocationSource(
    private val context: Context,
    private val fallback: LocationSource,
) : LocationSource {
    private val fused: FusedLocation? by lazy {
        runCatching { FusedLocation(context).takeIf { it.isSupported() }?.apply { initialize() } }
            .onFailure { Log.w(TAG, "FusedLocation init failed", it) }
            .getOrNull()
    }

    private fun usable(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED &&
            fused != null

    override fun isAvailable(): Boolean = usable() || fallback.isAvailable()

    override suspend fun currentFix(): LocationFix? {
        if (!usable()) return fallback.currentFix()
        val f = fused ?: return fallback.currentFix()
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<LocationFix?> { cont ->
                val listener = object : FusedLocationListener {
                    override fun onChanged(data: LocationData?) {
                        if (data == null || !cont.isActive) return
                        runCatching { f.unregisterFusedLocationCallback(this) }
                        cont.resume(LocationFix(data.latitude, data.longitude, null, data.timestampMillis))
                    }

                    override fun onError(e: Exception) {
                        Log.w(TAG, "fused location error", e)
                        runCatching { f.unregisterFusedLocationCallback(this) }
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { runCatching { f.unregisterFusedLocationCallback(listener) } }
                runCatching { f.registerFusedLocationCallback(listener) }.onFailure {
                    Log.w(TAG, "registerFusedLocationCallback failed", it)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val TAG = "FusedLocationSource"
        private const val FIX_TIMEOUT_MS = 5_000L
    }
}
