package com.framemayo.autolog.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Migration11To12Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-11-12-test.db"

    private fun open() = Room.databaseBuilder(context, AutoLogDatabase::class.java, name)
        .addMigrations(AutoLogDatabase.MIGRATION_11_12).allowMainThreadQueries().build()

    private fun trip(id: Long, status: TripStatus) = TripEntity(
        id = id, uuid = "uuid-$id", driverId = null, startedAt = id * 1000, endedAt = if (status == TripStatus.ACTIVE) null else id * 1000 + 500,
        startOdometerKm = 100f, endOdometerKm = if (status == TripStatus.COMPLETED) 110f else null, lastOdometerKm = 110f,
        lastSavedAt = id * 1000 + 500, status = status, sourceRevision = 3, serverVersion = 5, updatedAt = id * 1000 + 500,
    )

    @Test fun keepsTripsAndAddsAppAColumnsAndTables() = runBlocking {
        context.deleteDatabase(name)
        try {
            open().let { db ->
                db.tripDao().insert(trip(1, TripStatus.COMPLETED))
                db.tripDao().insert(trip(2, TripStatus.INTERRUPTED))
                db.tripDao().insert(trip(3, TripStatus.ACTIVE))
                db.close()
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                rewindToV11(it)
                it.execSQL("DROP TABLE room_master_table")
                it.version = 11
            }
            open().let { db ->
                val completed = db.tripDao().get(1)!!
                assertEquals(trip(1, TripStatus.COMPLETED).copy(endedBy = TripEndReason.MANUAL), completed)
                assertEquals(TripTrigger.MANUAL, db.tripDao().get(2)!!.startedBy)
                assertNull(db.tripDao().get(2)!!.endedBy)
                assertNull(db.tripDao().get(3)!!.endedBy)

                db.tripStopDao().insert(TripStopEntity(tripId = 3, startedAt = 3100, odometerKm = 105f))
                assertEquals(3100L, db.tripStopDao().open(3)!!.startedAt)
                db.tripStopDao().close(3, 3200)
                assertNull(db.tripStopDao().open(3))
                assertEquals(3200L, db.tripStopDao().forTrip(3).single().endedAt)

                db.energyEventDao().insert(
                    EnergyEventEntity(kind = EnergyEventKind.REFUEL, tripId = 1, detectedAt = 1600, odometerKm = 110f,
                        beforePercent = 20f, afterPercent = 90f, estimatedAmount = 35f, estimatedCostWon = 59_500),
                )
                assertEquals(59_500L, db.energyEventDao().forTrip(1).single().estimatedCostWon)
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
