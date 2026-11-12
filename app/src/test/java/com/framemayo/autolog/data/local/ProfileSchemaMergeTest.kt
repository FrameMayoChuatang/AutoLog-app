package com.framemayo.autolog.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 동일 버전 번호를 사용했던 main/시제품 DB를 실제 Room 검증과 함께 재개방한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileSchemaMergeTest {
    private inline fun <T> AutoLogDatabase.use(block: (AutoLogDatabase) -> T): T =
        try { block(this) } finally { close() }

    @Test fun mainV8PreservesSyncMetadata() = verify(8, false)
    @Test fun mainV9PreservesSyncMetadata() = verify(9, false)
    @Test fun prototypeV8PreservesAppearance() = verify(8, true)
    @Test fun prototypeV9PreservesExpression() = verify(9, true)

    // Robolectric의 SQLite는 DROP COLUMN을 지원하지 않아 원래 제약/인덱스를 유지해 구버전을 만든다.
    private fun removeColumn(db: SQLiteDatabase, table: String, column: String) {
        val schema = db.rawQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use {
            it.moveToFirst(); it.getString(0)
        }
        val indexes = db.rawQuery("SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? AND sql IS NOT NULL", arrayOf(table)).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        val columns = db.rawQuery("PRAGMA table_info($table)", null).use {
            buildList { while (it.moveToNext()) if (it.getString(1) != column) add("`${it.getString(1)}`") }
        }.joinToString(",")
        db.execSQL("CREATE TEMP TABLE old_rows AS SELECT $columns FROM $table")
        db.execSQL("DROP TABLE $table")
        db.execSQL(schema.replace(Regex(",\\s*`?$column`?\\s+[^,)]*"), ""))
        db.execSQL("INSERT INTO $table ($columns) SELECT $columns FROM old_rows")
        db.execSQL("DROP TABLE old_rows")
        indexes.forEach(db::execSQL)
    }

    private fun verify(version: Int, prototype: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "schema-merge-$version-$prototype.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AutoLogDatabase::class.java, name)
            .addMigrations(AutoLogDatabase.MIGRATION_8_10, AutoLogDatabase.MIGRATION_9_10)
            .allowMainThreadQueries().build()
        try {
            open().use { db ->
                db.driverDao().insert(DriverEntity(id = 1, name = "기존 운전자", department = "영업", remoteId = "server-id", locationConsent = true, consentUpdatedAt = 10, status = DriverStatus.ACTIVE, consentSentAt = 10))
                db.driverDao().saveProfile(DriverProfileEntity(1, "내 프로필", 2, true, 3))
                db.tripDao().insert(TripEntity(id = 1, driverId = 1, startedAt = 1, startOdometerKm = 20f, lastOdometerKm = 25f, lastSavedAt = 2, confirmSentAt = 40))
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE room_master_table")
                if (prototype) {
                    removeColumn(db, "drivers", "status")
                    removeColumn(db, "drivers", "consentSentAt")
                    removeColumn(db, "trips", "confirmSentAt")
                    if (version == 8) removeColumn(db, "driver_profiles", "expression")
                } else {
                    db.execSQL("DROP TABLE driver_profiles")
                    if (version == 8) removeColumn(db, "trips", "confirmSentAt")
                }
                db.version = version
            }
            // 두 번 열어 마이그레이션뿐 아니라 새 identity hash로 재시작도 검증한다.
            repeat(2) {
                open().use { db ->
                    assertEquals(10, db.openHelper.readableDatabase.version)
                    val driver = db.driverDao().byId(1)!!
                    assertEquals("server-id", driver.remoteId)
                    assertEquals(true, driver.locationConsent)
                    assertEquals(10L, driver.consentUpdatedAt)
                    assertEquals(if (prototype) null else DriverStatus.ACTIVE, driver.status)
                    assertEquals(if (prototype) null else 10L, driver.consentSentAt)
                    val trip = db.tripDao().get(1)!!
                    assertEquals(1L, trip.driverId)
                    assertEquals(25f, trip.lastOdometerKm)
                    assertEquals(if (!prototype && version == 9) 40L else null, trip.confirmSentAt)
                    val profile = db.driverDao().profile(1)
                    if (prototype) {
                        assertEquals("내 프로필", profile!!.displayName)
                        assertEquals(2, profile.color)
                        assertTrue(profile.hidden)
                        assertEquals(if (version == 9) 3 else 0, profile.expression)
                    } else assertNull(profile)
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}
