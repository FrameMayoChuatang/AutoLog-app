package com.framemayo.autolog.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.framemayo.autolog.data.repository.TripRepositoryImpl
import com.framemayo.autolog.data.sync.toConfirmPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// SQLite 테스트 엔진에 DROP COLUMN이 없어 원래 인덱스와 제약을 유지해 v10 구조를 재현한다.
internal fun removePurposeTextColumn(db: SQLiteDatabase) {
    val schema = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='trips'", null).use { it.moveToFirst(); it.getString(0) }
    val indexes = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='trips' AND sql IS NOT NULL", null).use {
        buildList { while (it.moveToNext()) add(it.getString(0)) }
    }
    val columns = db.rawQuery("PRAGMA table_info(trips)", null).use {
        buildList { while (it.moveToNext()) if (it.getString(1) != "purposeText") add("`${it.getString(1)}`") }
    }.joinToString(",")
    db.execSQL("CREATE TEMP TABLE old_trips AS SELECT $columns FROM trips")
    db.execSQL("DROP TABLE trips")
    db.execSQL(schema.replace(Regex(",\\s*`?purposeText`?\\s+[^,)]*"), ""))
    db.execSQL("INSERT INTO trips ($columns) SELECT $columns FROM old_trips")
    db.execSQL("DROP TABLE old_trips")
    indexes.forEach(db::execSQL)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PurposeMigrationTest {
    @Test fun versionTenKeepsEvidenceAndMemoAndNewPurposeSurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "purpose-v10.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AutoLogDatabase::class.java, name)
            .addMigrations(AutoLogDatabase.MIGRATION_10_11).allowMainThreadQueries().build()
        val original = TripEntity(id = 1, uuid = "original-uuid", driverId = 1, startedAt = 1, endedAt = 2,
            startOdometerKm = 100f, endOdometerKm = 110f, lastOdometerKm = 110f, lastSavedAt = 2,
            status = TripStatus.COMPLETED, purpose = TripPurpose.BUSINESS_GENERAL, memo = "기존 메모",
            serverVersion = 7, sourceRevision = 3, ackedRevision = 3, confirmSentAt = 8)
        try {
            open().let { db ->
                db.driverDao().insert(DriverEntity(id = 1, remoteId = "server-driver", name = "이름", department = "", locationConsent = true))
                db.driverDao().saveProfile(DriverProfileEntity(1, "표시 이름", 2, true, 4))
                db.tripDao().insert(original)
                db.close()
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                removePurposeTextColumn(it)
                it.execSQL("DROP TABLE room_master_table")
                it.version = 10
            }
            open().let { db ->
                assertEquals(original, db.tripDao().get(1))
                assertEquals("일반 업무", db.tripDao().get(1)!!.toConfirmPayload("server-driver", false)!!.purposeText)
                TripRepositoryImpl(db.tripDao()).setPurpose(1, TripPurpose.BUSINESS_GENERAL, purposeText = "거래처 방문")
                db.close()
            }
            open().let { db ->
                val saved = db.tripDao().get(1)!!
                assertEquals("거래처 방문", saved.purposeText)
                assertEquals("기존 메모", saved.memo)
                assertEquals(7L, saved.serverVersion)
                assertEquals(3L, saved.sourceRevision)
                assertEquals("original-uuid", saved.uuid)
                assertEquals(true, db.driverDao().byId(1)!!.locationConsent)
                assertEquals(true, db.driverDao().profile(1)!!.hidden)
                assertEquals(4, db.driverDao().profile(1)!!.expression)
                db.close()
            }
        } finally { context.deleteDatabase(name) }
    }
}
