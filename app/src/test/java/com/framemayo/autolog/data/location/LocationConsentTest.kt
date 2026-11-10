package com.framemayo.autolog.data.location

import com.framemayo.autolog.data.local.DriverDao
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.repository.DriverRepositoryImpl
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationConsentTest {
    @Test fun onlyConsentedBusinessTripsMayStoreLocation() {
        assertTrue(LocationPolicy.canStore(true, TripPurpose.BUSINESS_COMMUTE))
        assertTrue(LocationPolicy.canStore(true, TripPurpose.BUSINESS_GENERAL))
        assertFalse(LocationPolicy.canStore(true, TripPurpose.PERSONAL))
        assertFalse(LocationPolicy.canStore(true, TripPurpose.UNCONFIRMED))
    }

    @Test fun noConsentOrWithdrawnNeverStores() {
        for (p in TripPurpose.values()) {
            assertFalse(LocationPolicy.canStore(null, p))
            assertFalse(LocationPolicy.canStore(false, p))
        }
    }

    @Test fun consentCanBeGivenAndWithdrawnWithTimestamp() = runBlocking {
        val saved = mutableMapOf<Long, Pair<Boolean, Long>>()
        val dao = Proxy.newProxyInstance(DriverDao::class.java.classLoader, arrayOf(DriverDao::class.java)) { _, m, a ->
            when (m.name) {
                "observeAll" -> flowOf(emptyList<Any>())
                "setConsent" -> { saved[a[0] as Long] = (a[1] as Boolean) to (a[2] as Long); Unit }
                else -> error(m.name)
            }
        } as DriverDao
        var now = 10L
        val repo = DriverRepositoryImpl(dao) { now }

        repo.setLocationConsent(1, true)
        assertEquals(true to 10L, saved[1L])
        now = 20L
        repo.setLocationConsent(1, false) // 철회
        assertEquals(false to 20L, saved[1L])
    }
}
