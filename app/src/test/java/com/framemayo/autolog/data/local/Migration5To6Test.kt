package com.framemayo.autolog.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val UUID_A = "0a94e986-dfcf-4e4a-b490-f96876c62aed"

/** v5(운전자·목적 ID가 숫자) → v6(UUID 문자열) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration5To6Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-5-6-test.db"
    private var db: AutoLogDatabase? = null

    @Before fun createV5() {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use {
            it.execSQL(
                "CREATE TABLE drivers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, department TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, remoteId INTEGER, locationConsent INTEGER, consentUpdatedAt INTEGER)",
            )
            it.execSQL("CREATE UNIQUE INDEX index_drivers_remoteId ON drivers (remoteId)")
            it.execSQL("CREATE TABLE purposes (remoteId INTEGER NOT NULL, name TEXT NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(remoteId))")
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
            it.execSQL("INSERT INTO drivers VALUES (1, 'Kim', 'Sales', 100, 77, 1, 500)")
            it.execSQL("INSERT INTO drivers VALUES (5, 'Lee', 'HR', 200, NULL, NULL, NULL)")
            it.execSQL("INSERT INTO purposes VALUES (3, '영업 미팅', 1)")
            it.execSQL("INSERT INTO trips VALUES (1, '$UUID_A', 5, 1000, 2000, 100.0, 115.0, 115.0, 2000, 'COMPLETED', 'PERSONAL', NULL, 'SYNCED', 2000, 2000)")
            it.execSQL("INSERT INTO trip_locations VALUES (1, 37.5, 127.0, 5.0, 2000)")
            it.version = 5
        }
    }

    @After fun close() {
        db?.close()
        context.deleteDatabase(name)
    }

    private fun open(): AutoLogDatabase =
        Room.databaseBuilder(context, AutoLogDatabase::class.java, name)
            .addMigrations(AutoLogDatabase.MIGRATION_5_6, AutoLogDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    @Test fun driversKeepEverythingExceptOldNumericRemoteId() = runBlocking {
        val drivers = open().driverDao().observeAll().first().associateBy { it.id }
        assertEquals(setOf(1L, 5L), drivers.keys)
        val kim = drivers.getValue(1)
        assertEquals("Kim", kim.name)
        assertEquals("Sales", kim.department)
        assertEquals(100L, kim.createdAt)
        assertEquals(true, kim.locationConsent)
        assertEquals(500L, kim.consentUpdatedAt)
        assertNull(kim.remoteId)
        assertNull(drivers.getValue(5).locationConsent)
    }

    @Test fun tripsAndLocationsAreUntouched() = runBlocking {
        val room = open()
        val trip = room.tripDao().get(1)!!
        assertEquals(5L, trip.driverId)
        assertEquals(UUID_A, trip.uuid)
        assertEquals(37.5, room.tripLocationDao().get(1)!!.latitude, 0.0)
    }

    @Test fun purposesCacheIsRecreatedEmptyAndAcceptsUuids() = runBlocking {
        val room = open()
        assertEquals(0, room.purposeDao().observeActive().first().size)
        room.purposeDao().replaceAll(listOf(PurposeEntity(UUID_A, "업무")))
        assertEquals(UUID_A, room.purposeDao().observeActive().first().single().remoteId)
    }

    @Test fun driversAcceptUuidsAndKeepAutoIncrement() = runBlocking {
        val room = open()
        val id = room.driverDao().insert(DriverEntity(remoteId = UUID_A, name = "Park", department = "IT"))
        assertEquals(UUID_A, room.driverDao().byRemoteId(UUID_A)!!.remoteId)
        assertEquals(true, id > 5)
    }
}
