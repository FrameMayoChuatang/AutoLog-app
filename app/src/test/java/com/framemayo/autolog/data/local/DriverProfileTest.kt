package com.framemayo.autolog.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.framemayo.autolog.data.repository.DriverRepositoryImpl
import com.framemayo.autolog.data.repository.RemoteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriverProfileTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AutoLogDatabase::class.java).allowMainThreadQueries().build()
    private val repo = DriverRepositoryImpl(db.driverDao())

    @After fun close() { db.close(); context.deleteDatabase("profiles-migration.db") }

    @Test fun localAppearanceSurvivesRemoteRefreshAndHidingPreservesIdentity() = runBlocking {
        repo.upsertRemote(listOf(RemoteDriver("server-driver", "서버 이름", "영업", DriverStatus.ACTIVE)))
        val id = repo.all.first().single().id
        repo.editProfile(id, "내 이름", 2)
        repo.upsertRemote(listOf(RemoteDriver("server-driver", "변경된 서버 이름", "개발", DriverStatus.ACTIVE)))
        assertEquals("내 이름", repo.profiles.first().single().displayName)
        assertEquals(2, repo.profiles.first().single().color)
        assertTrue(repo.hideProfile(id))
        repo.upsertRemote(listOf(RemoteDriver("server-driver", "다시 동기화", "개발", DriverStatus.ACTIVE)))
        assertTrue(repo.profiles.first().isEmpty())
        assertEquals("server-driver", repo.remoteIdOf(id))
        assertEquals(1, repo.all.first().size)
    }

    @Test fun activeDriverCannotBeHiddenAndCompletedTripIsKept() = runBlocking {
        val id = repo.addProfile("운전자", 1)
        val trip = TripEntity(driverId = id, startedAt = 1, startOdometerKm = 10f, lastOdometerKm = 10f, lastSavedAt = 1)
        val tripId = db.tripDao().insert(trip)
        assertFalse(repo.hideProfile(id))
        db.tripDao().update(trip.copy(id = tripId, status = TripStatus.COMPLETED))
        assertTrue(repo.hideProfile(id))
        assertEquals(id, db.tripDao().get(tripId)?.driverId)
    }

    @Test fun orderingUsesDistinctDriversAcrossAllTrips() = runBlocking {
        val a = repo.addProfile("첫째", 0)
        val b = repo.addProfile("둘째", 0)
        for (i in 1..60) db.tripDao().insert(TripEntity(driverId = if (i == 1) a else b, startedAt = i.toLong(), startOdometerKm = 10f, lastOdometerKm = 10f, lastSavedAt = i.toLong(), status = TripStatus.COMPLETED))
        assertEquals(listOf(b, a), repo.profiles.first().map { it.driver.id })
    }

    @Test fun invalidNamesAndColorsDoNotCreateDrivers() = runBlocking {
        assertTrue(runCatching { repo.addProfile("  ", 0) }.isFailure)
        assertTrue(runCatching { repo.addProfile("이름", 5) }.isFailure)
        assertTrue(repo.all.first().isEmpty())
    }

    @Test fun profileCreationIsAtomic() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_profile BEFORE INSERT ON driver_profiles BEGIN SELECT RAISE(ABORT, 'fail'); END")
        assertTrue(runCatching { repo.addProfile("실패", 1) }.isFailure)
        assertTrue(repo.all.first().isEmpty())
    }

    @Test fun migrationAndReopenPreserveExistingDataAndNewSettings() = runBlocking {
        val name = "profiles-migration.db"
        context.deleteDatabase(name)
        val initial = Room.databaseBuilder(context, AutoLogDatabase::class.java, name).allowMainThreadQueries().build()
        val id = initial.driverDao().insert(DriverEntity(name = "기존 운전자", department = "영업", remoteId = "remote", locationConsent = true))
        val tripId = initial.tripDao().insert(TripEntity(driverId = id, startedAt = 1, startOdometerKm = 50f, lastOdometerKm = 55f, lastSavedAt = 2))
        initial.close()
        // main v9 구조에서 프로필 테이블을 추가하는 경로를 검증한다.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE driver_profiles")
            it.execSQL("DROP TABLE room_master_table")
            it.version = 9
        }
        fun open() = Room.databaseBuilder(context, AutoLogDatabase::class.java, name)
            .addMigrations(AutoLogDatabase.MIGRATION_9_10).allowMainThreadQueries().build()
        val migrated = open()
        try {
            val repository = DriverRepositoryImpl(migrated.driverDao())
            assertEquals("기존 운전자", repository.profiles.first().single().displayName)
            assertEquals(true, repository.consentOf(id))
            assertEquals("remote", repository.remoteIdOf(id))
            assertEquals(55f, migrated.tripDao().get(tripId)!!.lastOdometerKm)
            repository.editProfile(id, "수정 이름", 4)
        } finally { migrated.close() }
        val reopened = open()
        try {
            val profile = DriverRepositoryImpl(reopened.driverDao()).profiles.first().single()
            assertEquals("수정 이름", profile.displayName)
            assertEquals(4, profile.color)
        } finally { reopened.close() }
    }
}
