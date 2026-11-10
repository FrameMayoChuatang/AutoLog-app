package com.framemayo.autolog.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DriverEntity::class, TripEntity::class, PurposeEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AutoLogDatabase : RoomDatabase() {
    abstract fun driverDao(): DriverDao
    abstract fun tripDao(): TripDao
    abstract fun purposeDao(): PurposeDao

    companion object {
        fun create(context: Context): AutoLogDatabase =
            Room.databaseBuilder(context, AutoLogDatabase::class.java, "autolog.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drivers ADD COLUMN locationConsent INTEGER")
                db.execSQL("ALTER TABLE drivers ADD COLUMN consentUpdatedAt INTEGER")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drivers ADD COLUMN remoteId INTEGER")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_drivers_remoteId ON drivers (remoteId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS purposes (remoteId INTEGER NOT NULL, name TEXT NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(remoteId))")
                db.execSQL(
                    "CREATE TABLE trips_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, driverId INTEGER, " +
                        "startedAt INTEGER NOT NULL, endedAt INTEGER, startOdometerKm REAL NOT NULL, endOdometerKm REAL, " +
                        "lastOdometerKm REAL NOT NULL, lastSavedAt INTEGER NOT NULL, status TEXT NOT NULL, purpose TEXT NOT NULL, " +
                        "memo TEXT, syncState TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO trips_new SELECT id, lower(hex(randomblob(16))), driverId, startedAt, endedAt, startOdometerKm, " +
                        "endOdometerKm, lastOdometerKm, lastSavedAt, status, purpose, memo, " +
                        "CASE WHEN synced = 1 THEN 'SYNCED' ELSE 'PENDING' END, lastSavedAt FROM trips",
                )
                db.execSQL("DROP TABLE trips")
                db.execSQL("ALTER TABLE trips_new RENAME TO trips")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_status ON trips (status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_startedAt ON trips (startedAt)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_trips_uuid ON trips (uuid)")
            }
        }
    }
}
