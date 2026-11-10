package com.framemayo.autolog.data.location

import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripLocationDao
import com.framemayo.autolog.data.local.TripLocationEntity
import com.framemayo.autolog.data.local.TripPurpose

data class LocationFix(val latitude: Double, val longitude: Double, val accuracyM: Float?, val capturedAt: Long)

interface LocationSource {
    fun isAvailable(): Boolean
    suspend fun currentFix(): LocationFix?
}

enum class ArrivalResult { SAVED, NO_CONSENT, UNAVAILABLE, NO_FIX }

class ArrivalLocationRecorder(
    private val dao: TripLocationDao,
    private val source: LocationSource,
) {
    suspend fun recordArrival(trip: TripEntity, consent: Boolean?): ArrivalResult {
        if (!LocationPolicy.canCapture(consent)) return ArrivalResult.NO_CONSENT
        if (!source.isAvailable()) return ArrivalResult.UNAVAILABLE
        val fix = source.currentFix() ?: return ArrivalResult.NO_FIX
        dao.upsert(TripLocationEntity(trip.id, fix.latitude, fix.longitude, fix.accuracyM, fix.capturedAt))
        return ArrivalResult.SAVED
    }

    suspend fun settle(tripId: Long, consent: Boolean?, purpose: TripPurpose) {
        if (LocationPolicy.shouldPurge(consent, purpose)) dao.delete(tripId)
    }

    suspend fun purgeDriver(driverId: Long) = dao.deleteByDriver(driverId)
}
