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

    @Test fun hiddenProfileNameRemainsAvailableForHistoricalTripsOnly() = runBlocking {
        val id = repo.addProfile("서버 등록 이름", 0)
        repo.editProfile(id, "표시 이름", 2)
        repo.hideProfile(id)
        val settings = repo.profileSettings.first()
        val state = com.framemayo.autolog.ui.MainUiState(
            drivers = repo.all.first(), profiles = repo.profiles.first(),
            profileNames = settings.mapNotNull { p -> p.displayName?.let { p.driverId to it } }.toMap(),
        )
        assertEquals("표시 이름", state.driverName(id))
        assertTrue(state.profiles.isEmpty())
        assertEquals("서버 등록 이름", repo.find(id)?.name)
        assertEquals("운전자 없음", state.driverName(null))
    }

    @Test fun localAppearanceSurvivesRemoteRefreshAndHidingPreservesIdentity() = runBlocking {
        repo.upsertRemote(listOf(RemoteDriver("server-driver", "서버 이름", "영업", DriverStatus.ACTIVE)))
        val id = repo.all.first().single().id
        repo.editProfile(id, "내 이름", 2, 1)
        repo.upsertRemote(listOf(RemoteDriver("server-driver", "변경된 서버 이름", "개발", DriverStatus.ACTIVE)))
        assertEquals("내 이름", repo.profiles.first().single().displayName)
        assertEquals(2, repo.profiles.first().single().color)
        assertEquals(1, repo.profiles.first().single().expression)
        assertTrue(repo.hideProfile(id))
        repo.upsertRemote(listOf(RemoteDriver("server-driver", "다시 동기화", "개발", DriverStatus.ACTIVE)))
        assertTrue(repo.profiles.first().isEmpty())
        assertEquals("server-driver", repo.remoteIdOf(id))
        assertEquals(1, repo.all.first().size)
    }

    @Test fun inactiveServerDriverIsNotSelectableAndProfileSurvivesReactivation() = runBlocking {
        repo.upsertRemote(listOf(RemoteDriver("remote", "운전자", "영업", DriverStatus.ACTIVE)))
        val id = repo.all.first().single().id
        repo.editProfile(id, "별명", 2, 0)
        repo.upsertRemote(listOf(RemoteDriver("remote", "운전자", "영업", DriverStatus.INACTIVE)))
        assertTrue(repo.profiles.first().isEmpty())
        repo.upsertRemote(listOf(RemoteDriver("remote", "운전자", "영업", DriverStatus.ACTIVE)))
        assertEquals("별명", repo.profiles.first().single().displayName)
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
        assertTrue(runCatching { repo.addProfile("이름", 7) }.isFailure)
        assertTrue(runCatching { repo.addProfile("이름", 0, 7) }.isFailure)
        assertTrue(runCatching { repo.addProfile("이름", 0, -1) }.isFailure)
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
            assertEquals(0, repository.profiles.first().single().expression)
            assertEquals(true, repository.consentOf(id))
            assertEquals("remote", repository.remoteIdOf(id))
            assertEquals(55f, migrated.tripDao().get(tripId)!!.lastOdometerKm)
            repository.editProfile(id, "수정 이름", 4, 6)
        } finally { migrated.close() }
        val reopened = open()
        try {
            val profile = DriverRepositoryImpl(reopened.driverDao()).profiles.first().single()
            assertEquals("수정 이름", profile.displayName)
            assertEquals(4, profile.color)
            assertEquals(6, profile.expression)
        } finally { reopened.close() }
    }

    @Test fun versionEightMigrationPreservesCustomProfilesAndDefaultsExpressions() = runBlocking {
        val name = "profiles-migration.db"
        val initial = Room.databaseBuilder(context, AutoLogDatabase::class.java, name).allowMainThreadQueries().build()
        val repository = DriverRepositoryImpl(initial.driverDao())
        val id = repository.addProfile("사용자", 2, 1)
        repository.editProfile(id, "표시 이름", 4, 6)
        val hidden = repository.addProfile("숨김", 1, 2)
        repository.hideProfile(hidden)
        val tripId = initial.tripDao().insert(TripEntity(driverId = id, startedAt = 1, startOdometerKm = 10f, lastOdometerKm = 12f, lastSavedAt = 2))
        initial.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TABLE driver_profiles_v8 (driverId INTEGER NOT NULL, displayName TEXT, color INTEGER NOT NULL, hidden INTEGER NOT NULL, PRIMARY KEY(driverId), FOREIGN KEY(driverId) REFERENCES drivers(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            it.execSQL("INSERT INTO driver_profiles_v8 SELECT driverId, displayName, color, hidden FROM driver_profiles")
            it.execSQL("DROP TABLE driver_profiles")
            it.execSQL("ALTER TABLE driver_profiles_v8 RENAME TO driver_profiles")
            it.execSQL("DROP TABLE room_master_table")
            it.version = 8
        }
        val migrated = Room.databaseBuilder(context, AutoLogDatabase::class.java, name)
            .addMigrations(AutoLogDatabase.MIGRATION_8_10).allowMainThreadQueries().build()
        try {
            val profile = migrated.driverDao().observeProfiles().first().single()
            assertEquals(id, profile.driver.id)
            assertEquals("표시 이름", profile.displayName)
            assertEquals(4, profile.color)
            assertEquals(0, profile.expression)
            assertTrue(migrated.driverDao().profile(hidden)!!.hidden)
            assertEquals(id, migrated.tripDao().get(tripId)!!.driverId)
            assertEquals(12f, migrated.tripDao().get(tripId)!!.lastOdometerKm)
        } finally { migrated.close() }
    }

}
