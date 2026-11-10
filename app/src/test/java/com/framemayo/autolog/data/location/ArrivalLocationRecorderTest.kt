package com.framemayo.autolog.data.location

import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripLocationDao
import com.framemayo.autolog.data.local.TripLocationEntity
import com.framemayo.autolog.data.local.TripPurpose
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSource(var available: Boolean = true, var fix: LocationFix? = LocationFix(37.5, 127.0, 5f, 1L)) :
    LocationSource {
    var calls = 0
    override fun isAvailable() = available
    override suspend fun currentFix(): LocationFix? { calls++; return fix }
}

class ArrivalLocationRecorderTest {
    private val rows = mutableMapOf<Long, TripLocationEntity>()
    private val driverOf = mapOf(1L to 10L, 2L to 20L)   // tripId -> driverId
    private val dao = Proxy.newProxyInstance(TripLocationDao::class.java.classLoader, arrayOf(TripLocationDao::class.java)) { _, m, a ->
        when (m.name) {
            "upsert" -> { val l = a[0] as TripLocationEntity; rows[l.tripId] = l; Unit }
            "get" -> rows[a[0] as Long]
            "delete" -> { rows.remove(a[0] as Long); Unit }
            "deleteByDriver" -> { rows.keys.removeAll { driverOf[it] == a[0] }; Unit }
            else -> error(m.name)
        }
    } as TripLocationDao
    private val source = FakeSource()
    private val recorder = ArrivalLocationRecorder(dao, source)
    private fun trip(id: Long) = TripEntity(id = id, driverId = driverOf[id], startedAt = 0, startOdometerKm = 0f, lastOdometerKm = 1f, lastSavedAt = 0)

    @Test fun savesWhenConsentedAndAvailable() = runBlocking {
        assertEquals(ArrivalResult.SAVED, recorder.recordArrival(trip(1), true))
        assertNotNull(rows[1L])
    }

    @Test fun noConsentOrWithdrawnNeverTouchesLocationApi() = runBlocking {
        assertEquals(ArrivalResult.NO_CONSENT, recorder.recordArrival(trip(1), null))
        assertEquals(ArrivalResult.NO_CONSENT, recorder.recordArrival(trip(1), false))
        assertEquals(0, source.calls)
        assertTrue(rows.isEmpty())
    }

    @Test fun unavailableApiIsDisabledNotAnError() = runBlocking {
        source.available = false
        assertEquals(ArrivalResult.UNAVAILABLE, recorder.recordArrival(trip(1), true))
        assertEquals(0, source.calls)
        source.available = true; source.fix = null
        assertEquals(ArrivalResult.NO_FIX, recorder.recordArrival(trip(1), true))
        assertTrue(rows.isEmpty())
    }

    @Test fun personalPurposeDeletesProvisionalLocation() = runBlocking {
        recorder.recordArrival(trip(1), true)
        recorder.settle(1, true, TripPurpose.UNCONFIRMED)
        assertNotNull(rows[1L])                                  // 미확인은 임시 보관
        recorder.settle(1, true, TripPurpose.PERSONAL)
        assertNull(rows[1L])
    }

    @Test fun businessPurposeKeepsLocation() = runBlocking {
        recorder.recordArrival(trip(1), true)
        recorder.settle(1, true, TripPurpose.BUSINESS_GENERAL)
        assertNotNull(rows[1L])
    }

    @Test fun withdrawalPurgesOnlyThatDriversLocations() = runBlocking {
        recorder.recordArrival(trip(1), true); recorder.recordArrival(trip(2), true)
        recorder.purgeDriver(10L)
        assertFalse(rows.containsKey(1L))
        assertTrue(rows.containsKey(2L))
    }
}
