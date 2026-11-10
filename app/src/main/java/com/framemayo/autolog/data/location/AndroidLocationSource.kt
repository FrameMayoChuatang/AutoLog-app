package com.framemayo.autolog.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.util.Consumer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@SuppressLint("MissingPermission") // 권한 확인 후 호출
class AndroidLocationSource(private val context: Context) : LocationSource {
    private val manager get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun isAvailable(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            provider() != null

    private fun provider(): String? {
        val m = manager ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { m.isProviderEnabled(it) }
    }

    override suspend fun currentFix(): LocationFix? {
        val m = manager ?: return null
        val p = provider() ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<LocationFix?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() } // 타임아웃 시 GPS 요청도 중단
                LocationManagerCompat.getCurrentLocation(m, p, signal, ContextCompat.getMainExecutor(context), Consumer<Location?> { loc ->
                    if (cont.isActive) {
                        cont.resume(
                            loc?.let {
                                LocationFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time)
                            },
                        )
                    }
                })
            }
        }
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 5_000L
    }
}
