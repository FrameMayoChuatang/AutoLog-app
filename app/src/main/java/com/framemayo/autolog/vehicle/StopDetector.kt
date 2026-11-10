package com.framemayo.autolog.vehicle

class StopDetector(private val thresholdMs: Long = 45_000L) {
    private var lastKm: Float? = null
    private var lastChangedAt: Long = 0L

    fun onOdometer(km: Float, now: Long): Boolean {
        if (lastKm == null || km != lastKm) {
            lastKm = km
            lastChangedAt = now
        }
        return now - lastChangedAt >= thresholdMs
    }

    fun stationaryMs(now: Long): Long = if (lastKm == null) 0 else now - lastChangedAt
}
