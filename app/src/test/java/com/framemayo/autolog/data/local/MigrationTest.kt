package com.framemayo.autolog.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class) // AutoLogApp(WorkManager 예약) 대신 빈 Application
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"
    private var db: AutoLogDatabase? = null

    @Before fun createV1() {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use {
            it.execSQL("CREATE TABLE drivers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, department TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            it.execSQL(
                "CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, driverId INTEGER, startedAt INTEGER NOT NULL, " +
                    "endedAt INTEGER, startOdometerKm REAL NOT NULL, endOdometerKm REAL, lastOdometerKm REAL NOT NULL, " +
                    "lastSavedAt INTEGER NOT NULL, status TEXT NOT NULL, purpose TEXT NOT NULL, memo TEXT, synced INTEGER NOT NULL)",
            )
            it.execSQL("CREATE INDEX index_trips_status ON trips (status)")
            it.execSQL("CREATE INDEX index_trips_startedAt ON trips (startedAt)")
            it.execSQL("INSERT INTO drivers VALUES (1, 'Kim', 'Sales', 100)")
            it.execSQL("INSERT INTO trips VALUES (1, 1, 1000, 2000, 100.0, 115.0, 115.0, 2000, 'COMPLETED', 'BUSINESS_GENERAL', 'memo', 1)")
            it.execSQL("INSERT INTO trips VALUES (2, 1, 3000, NULL, 115.0, NULL, 120.0, 3500, 'INTERRUPTED', 'UNCONFIRMED', NULL, 0)")
            it.version = 1
        }
    }

    @After fun close() {
        db?.close()
        context.deleteDatabase(name)
    }

    private fun open(): AutoLogDatabase =
        Room.databaseBuilder(context, AutoLogDatabase::class.java, name)
            .addMigrations(
                AutoLogDatabase.MIGRATION_1_2, AutoLogDatabase.MIGRATION_2_3,
                AutoLogDatabase.MIGRATION_3_4, AutoLogDatabase.MIGRATION_4_5, AutoLogDatabase.MIGRATION_5_6, AutoLogDatabase.MIGRATION_6_7, AutoLogDatabase.MIGRATION_7_8, AutoLogDatabase.MIGRATION_8_9, AutoLogDatabase.MIGRATION_8_10, AutoLogDatabase.MIGRATION_9_10, AutoLogDatabase.MIGRATION_10_11,
            )
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    private fun AutoLogDatabase.rows(sql: String): List<Map<String, Any?>> =
        openHelper.readableDatabase.query(sql).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).associate { i ->
                        c.getColumnName(i) to when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> c.getString(i)
                        }
                    })
                }
            }
        }

    @Test fun v1MigratesToLatestKeepingData() {
        val room = open()
        assertEquals(11, room.openHelper.readableDatabase.version)

        val trips = room.rows("SELECT * FROM trips ORDER BY id")
        assertEquals(2, trips.size)
        val (t1, t2) = trips
        assertEquals(115.0, t1["endOdometerKm"])
        assertEquals("BUSINESS_GENERAL", t1["purpose"])
        assertEquals("memo", t1["memo"])
        assertEquals("PENDING", t1["syncState"])
        assertNull(t1["syncedAt"])
        assertEquals(1L, t1["sourceRevision"])
        assertEquals(0L, t1["ackedRevision"])
        assertEquals(0L, t1["serverVersion"])
        assertNull(t1["confirmSentAt"])
        assertEquals("PENDING", t2["syncState"])
        assertNull(t2["syncedAt"])
        assertEquals(3500L, t2["updatedAt"])

        val drivers = room.rows("SELECT * FROM drivers")
        assertEquals("Kim", drivers.single()["name"])
        assertNull(drivers.single()["remoteId"])
        assertNull(drivers.single()["locationConsent"])
        assertNull(drivers.single()["status"])
        assertNull(drivers.single()["consentSentAt"])
    }

    @Test fun migratedUuidsAreStandardAndUnique() {
        val uuids = open().rows("SELECT uuid FROM trips").map { it["uuid"] as String }
        val format = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        uuids.forEach { assertTrue(it, format.matches(it)) }
        assertEquals(uuids.size, uuids.toSet().size)
        uuids.forEach { java.util.UUID.fromString(it) }
    }

    @Test fun migratedTripLocationsCascadeOnTripDelete() {
        val w = open().openHelper.writableDatabase
        w.execSQL("INSERT INTO trip_locations VALUES (1, 37.5, 127.0, 5.0, 2000)")
        w.execSQL("DELETE FROM trips WHERE id = 1")
        assertEquals(0, db!!.rows("SELECT * FROM trip_locations").size)
    }
}
