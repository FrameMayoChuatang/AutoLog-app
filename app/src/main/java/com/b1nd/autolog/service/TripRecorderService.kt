package com.b1nd.autolog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.b1nd.autolog.R
import com.b1nd.autolog.container
import com.b1nd.autolog.vehicle.OdometerValidator
import com.b1nd.autolog.vehicle.StopDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TripRecorderService : LifecycleService() {

    private val stopDetector = StopDetector()

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        RecorderState.update { it.copy(serviceRunning = true) }

        val vehicle = container.vehicle
        val trips = container.trips

        lifecycleScope.launch {
            trips.recoverInterrupted()?.let { Log.i(TAG, "interrupted trip recovered: ${it.id}") }

            runCatching { vehicle.initialize() }.onFailure {
                Log.e(TAG, "Vehicle SDK initialize failed", it)
                return@launch
            }

            launch { collectOdometer() }
            launch { collectGear() }
            launch { heartbeatLoop() }
        }
    }

    private suspend fun collectOdometer() {
        container.vehicle.odometerKm().collect { km ->
            val now = System.currentTimeMillis()
            val last = RecorderState.snapshot.value.odometerKm
            if (!OdometerValidator.isValid(km, last)) return@collect
            val stop = stopDetector.onOdometer(km!!, now)
            RecorderState.update {
                it.copy(
                    odometerKm = km,
                    odometerUpdatedAt = now,
                    stationaryMs = stopDetector.stationaryMs(now),
                    stopCandidate = stop,
                )
            }
        }
    }

    private suspend fun collectGear() {
        runCatching {
            container.vehicle.gear().collect { gear -> RecorderState.update { it.copy(gear = gear) } }
        }.onFailure { Log.w(TAG, "gear subscribe failed", it) }
    }

    private suspend fun heartbeatLoop() {
        while (lifecycleScope.isActive) {
            RecorderState.snapshot.value.odometerKm?.let { container.trips.heartbeat(it) }
            delay(HEARTBEAT_MS)
        }
    }

    override fun onDestroy() {
        RecorderState.update { it.copy(serviceRunning = false) }
        runCatching { container.vehicle.release() }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "운행 기록", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("운행 기록 준비 중")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "TripRecorderService"
        private const val CHANNEL = "trip_recorder"
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_MS = 1_000L

        fun start(context: Context) {
            if (RecorderState.snapshot.value.serviceRunning) return
            context.startForegroundService(Intent(context, TripRecorderService::class.java))
        }
    }
}
