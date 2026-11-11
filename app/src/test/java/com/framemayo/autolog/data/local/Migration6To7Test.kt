package com.framemayo.autolog.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration6To7Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-6-7-test.db"
    private var db: AutoLogDatabase? = null

    @Before fun createV6() {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use {
            it.execSQL(
                "CREATE TABLE drivers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, remoteId TEXT, name TEXT NOT NULL, " +
                    "department TEXT NOT NULL, createdAt INTEGER NOT NULL, locationConsent INTEGER, consentUpdatedAt INTEGER)",
            )
            it.execSQL("CREATE UNIQUE INDEX index_drivers_remoteId ON drivers (remoteId)")
            it.execSQL("CREATE TABLE purposes (remoteId TEXT NOT NULL, name TEXT NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(remoteId))")
            it.execSQL(
                "CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, driverId INTEGER, startedAt INTEGER NOT NULL, " +
                    "endedAt INTEGER, startOdometerKm REAL NOT NULL, endOdometerKm REAL, lastOdometerKm REAL NOT NULL, lastSavedAt INTEGER NOT NULL, " +
                    "status TEXT NOT NULL, purpose TEXT NOT NULL, memo TEXT, syncState TEXT NOT NULL, updatedAt INTEGER NOT NULL, syncedAt INTEGER)",
            )
            it.execSQL("CREATE INDEX index_trips_status ON trips (status)")
            it.execSQL("CREATE INDEX index_trips_startedAt ON trips (startedAt)")
            it.execSQL("CREATE UNIQUE INDEX index_trips_uuid ON trips (uuid)")
            it.execSQL(
                "CREATE TABLE trip_locations (tripId INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracyM REAL, " +
                    "capturedAt INTEGER NOT NULL, PRIMARY KEY(tripId), FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            it.execSQL("INSERT INTO trips VALUES (1, 'a0000000-0000-4000-8000-000000000001', NULL, 1000, 2000, 100.0, 110.0, 110.0, 2000, 'COMPLETED', 'PERSONAL', 'm', 'SYNCED', 2000, 2500)")
            it.execSQL("INSERT INTO trips VALUES (2, 'a0000000-0000-4000-8000-000000000002', NULL, 3000, 4000, 110.0, 120.0, 120.0, 4000, 'COMPLETED', 'UNCONFIRMED', NULL, 'FAILED', 4000, NULL)")
            it.execSQL("INSERT INTO trips VALUES (3, 'a0000000-0000-4000-8000-000000000003', NULL, 5000, NULL, 120.0, NULL, 125.0, 5500, 'ACTIVE', 'UNCONFIRMED', NULL, 'PENDING', 5000, NULL)")
            it.version = 6
        }
    }

    @After fun close() {
        db?.close()
        context.deleteDatabase(name)
    }

    private fun open(): AutoLogDatabase =
        Room.databaseBuilder(context, AutoLogDatabase::class.java, name)
            .addMigrations(AutoLogDatabase.MIGRATION_6_7, AutoLogDatabase.MIGRATION_7_8, AutoLogDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    @Test fun everyTripBecomesPendingWithInitialVersions() = runBlocking {
        val dao = open().tripDao()
        for (id in 1L..3L) {
            val t = dao.get(id)!!
            assertEquals("trip $id", SyncState.PENDING, t.syncState)
            assertNull(t.syncedAt)
            assertEquals(1L, t.sourceRevision)
            assertEquals(0L, t.ackedRevision)
            assertEquals(0L, t.serverVersion)
        }
        assertEquals(listOf(1L, 2L, 3L), dao.pendingSync().map { it.id })
    }

    @Test fun measurementsAndPurposesAreUntouched() = runBlocking {
        val dao = open().tripDao()
        val t = dao.get(1)!!
        assertEquals(110.0f, t.endOdometerKm)
        assertEquals(TripPurpose.PERSONAL, t.purpose)
        assertEquals("m", t.memo)
        assertEquals(TripStatus.ACTIVE, dao.get(3)!!.status)
    }
}
