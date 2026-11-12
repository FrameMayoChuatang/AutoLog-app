package com.framemayo.autolog.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DriverEntity::class, TripEntity::class, PurposeEntity::class, TripLocationEntity::class, DriverProfileEntity::class],
    version = 10,
    exportSchema = false,
)
abstract class AutoLogDatabase : RoomDatabase() {
    abstract fun driverDao(): DriverDao
    abstract fun tripDao(): TripDao
    abstract fun purposeDao(): PurposeDao
    abstract fun tripLocationDao(): TripLocationDao

    companion object {
        fun create(context: Context): AutoLogDatabase =
            Room.databaseBuilder(context, AutoLogDatabase::class.java, "autolog.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_8_10, MIGRATION_9_10)
                .build()

        // main과 프로필 시제품이 v8/v9를 각각 사용했으므로 버전뿐 아니라 실제 열을 확인한다.
        // 기존 프로필 표정도 보존해 개발 기기의 설정을 초기화하지 않는다.
        private fun mergeProfileSchema(db: SupportSQLiteDatabase) {
            fun addColumnIfMissing(table: String, column: String, definition: String) {
                val exists = db.query("PRAGMA table_info($table)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    var found = false
                    while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
                    found
                }
                if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
            }
            addColumnIfMissing("drivers", "status", "TEXT")
            addColumnIfMissing("drivers", "consentSentAt", "INTEGER")
            addColumnIfMissing("trips", "confirmSentAt", "INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS driver_profiles (driverId INTEGER NOT NULL, displayName TEXT, color INTEGER NOT NULL, " +
                    "hidden INTEGER NOT NULL, expression INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(driverId), " +
                    "FOREIGN KEY(driverId) REFERENCES drivers(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            addColumnIfMissing("driver_profiles", "expression", "INTEGER NOT NULL DEFAULT 0")
        }

        val MIGRATION_8_10 = object : Migration(8, 10) {
            override fun migrate(db: SupportSQLiteDatabase) = mergeProfileSchema(db)
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) = mergeProfileSchema(db)
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN confirmSentAt INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drivers ADD COLUMN status TEXT")
                db.execSQL("ALTER TABLE drivers ADD COLUMN consentSentAt INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN sourceRevision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE trips ADD COLUMN ackedRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN serverVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE trips SET syncState = 'PENDING', syncedAt = NULL WHERE syncState != 'PENDING'")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE drivers_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, remoteId TEXT, name TEXT NOT NULL, " +
                        "department TEXT NOT NULL, createdAt INTEGER NOT NULL, locationConsent INTEGER, consentUpdatedAt INTEGER)",
                )
                db.execSQL(
                    "INSERT INTO drivers_new (id, name, department, createdAt, locationConsent, consentUpdatedAt) " +
                        "SELECT id, name, department, createdAt, locationConsent, consentUpdatedAt FROM drivers",
                )
                db.execSQL("DROP TABLE drivers")
                db.execSQL("ALTER TABLE drivers_new RENAME TO drivers")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_drivers_remoteId ON drivers (remoteId)")
                db.execSQL("DROP TABLE purposes")
                db.execSQL("CREATE TABLE purposes (remoteId TEXT NOT NULL, name TEXT NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(remoteId))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN syncedAt INTEGER")
                db.execSQL("UPDATE trips SET syncedAt = updatedAt WHERE syncState = 'SYNCED'")
                db.execSQL(
                    "UPDATE trips SET uuid = substr(uuid, 1, 8) || '-' || substr(uuid, 9, 4) || '-' || " +
                        "substr(uuid, 13, 4) || '-' || substr(uuid, 17, 4) || '-' || substr(uuid, 21, 12) " +
                        "WHERE length(uuid) = 32",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trip_locations (tripId INTEGER NOT NULL, latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, accuracyM REAL, capturedAt INTEGER NOT NULL, PRIMARY KEY(tripId), " +
                        "FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

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
