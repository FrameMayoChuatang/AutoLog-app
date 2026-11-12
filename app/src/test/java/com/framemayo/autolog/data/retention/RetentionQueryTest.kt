package com.framemayo.autolog.data.retention

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.framemayo.autolog.data.local.AutoLogDatabase
import com.framemayo.autolog.data.local.SyncState
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RetentionQueryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AutoLogDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.tripDao()

    @After fun close() = db.close()

    private suspend fun synced(purpose: TripPurpose, status: TripStatus = TripStatus.COMPLETED): Long = dao.insert(
        TripEntity(
            driverId = null, startedAt = 0, startOdometerKm = 1f, lastOdometerKm = 2f, lastSavedAt = 1,
            status = status, purpose = purpose, syncState = SyncState.SYNCED, syncedAt = 10,
        ),
    )

    @Test fun purgeSkipsUnconfirmedButDeletesConfirmed() = runBlocking {
        val unconfirmed = synced(TripPurpose.UNCONFIRMED)
        val interruptedUnconfirmed = synced(TripPurpose.UNCONFIRMED, TripStatus.INTERRUPTED)
        val personal = synced(TripPurpose.PERSONAL)
        val business = synced(TripPurpose.BUSINESS_GENERAL)

        assertEquals(2, dao.deleteSyncedBefore(cutoff = 100))
        assertNotNull(dao.get(unconfirmed))
        assertNotNull(dao.get(interruptedUnconfirmed))
        assertNull(dao.get(personal))
        assertNull(dao.get(business))
    }
}
