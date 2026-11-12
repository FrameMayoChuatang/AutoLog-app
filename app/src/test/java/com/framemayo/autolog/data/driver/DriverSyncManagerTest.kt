package com.framemayo.autolog.data.driver

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.framemayo.autolog.data.local.AutoLogDatabase
import com.framemayo.autolog.data.local.DriverStatus
import com.framemayo.autolog.data.repository.DriverRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriverSyncManagerTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AutoLogDatabase::class.java)
        .allowMainThreadQueries().build()
    private var now = 1_000L
    private var pendingCalls = 0
    private var linked = true
    private val repo = DriverRepositoryImpl(db.driverDao(), clock = { now++ }, onPending = { pendingCalls++ })
    private val api = FakeDriverApi()
    private val sync = DriverSyncManager(repo, api, isLinked = { linked })

    @After fun close() = db.close()

    private suspend fun drivers() = repo.snapshot()

    @Test fun serverDriversAreAddedWithStatusButWithoutServerConsent() = runBlocking {
        api.serverDriver("Kim", "영업")
        api.serverDriver("Lee", "인사", DriverStatus.PENDING)
        assertTrue(sync.sync())
        val all = drivers().associateBy { it.name }
        assertEquals(DriverStatus.ACTIVE, all.getValue("Kim").status)
        assertEquals(DriverStatus.PENDING, all.getValue("Lee").status)
        assertNull(all.getValue("Kim").locationConsent)
    }

    @Test fun profileCreationSchedulesAndRegistersDriver() = runBlocking {
        val id = repo.addProfile("프로필", 2, 0)
        assertEquals(1, pendingCalls)
        assertTrue(sync.sync())
        assertEquals(id, drivers().single().id)
        assertEquals(DriverStatus.PENDING, drivers().single().status)
        assertEquals(1, api.registered.size)
    }

    @Test fun localDriverIsRegisteredPendingAndLinkedOnlyOnce() = runBlocking {
        repo.add("Park", "IT")
        assertTrue(sync.sync())
        assertEquals(listOf("Park" to "IT"), api.registered)
        val d = drivers().single()
        assertEquals(api.server.single().remoteId, d.remoteId)
        assertEquals(DriverStatus.PENDING, d.status)

        assertTrue(sync.sync())
        assertEquals(1, api.registered.size)
        assertEquals(1, drivers().size)
    }

    @Test fun uniqueNameAndDepartmentMatchLinksInsteadOfRegistering() = runBlocking {
        val s = api.serverDriver("Kim", "Sales")
        val id = repo.add("  kim ", "sales")
        assertTrue(sync.sync())
        assertEquals(0, api.registered.size)
        assertEquals(1, drivers().size)
        assertEquals(s.remoteId, drivers().single { it.id == id }.remoteId)
    }

    @Test fun ambiguousServerMatchRegistersNewDriverInstead() = runBlocking {
        api.serverDriver("Kim", "Sales")
        api.serverDriver("Kim", "Sales")
        repo.add("Kim", "Sales")
        assertTrue(sync.sync())
        assertEquals(1, api.registered.size)
    }

    @Test fun twoLocalDriversWithSameKeyAreNotMergedIntoOneServerDriver() = runBlocking {
        val s = api.serverDriver("Kim", "Sales")
        repo.add("Kim", "Sales")
        repo.add("Kim", "Sales")
        assertTrue(sync.sync())
        assertEquals(2, api.registered.size)
        assertTrue(drivers().none { it.remoteId == s.remoteId && it.id <= 2 })
    }

    @Test fun lostRegisterResponseLinksOnNextSyncWithoutDuplicate() = runBlocking {
        repo.add("Park", "IT")
        api.loseNextRegisterResponse = true
        assertFalse(sync.sync())
        assertNull(drivers().single { it.id == 1L }.remoteId)

        assertTrue(sync.sync())
        assertEquals(1, api.registered.size)
        assertEquals(api.server.single().remoteId, drivers().single { it.id == 1L }.remoteId)
    }

    @Test fun blankDepartmentAndLongNameFitServerRules() = runBlocking {
        repo.add("N".repeat(120), "   ")
        assertTrue(sync.sync())
        val (name, dept) = api.registered.single()
        assertEquals(80, name.length)
        assertEquals("미지정", dept)
    }

    @Test fun consentIsSentOnceAndResentWhenChanged() = runBlocking {
        val id = repo.add("Park", "IT")
        sync.sync()
        repo.setLocationConsent(id, true)
        assertTrue(sync.sync())
        assertEquals(1, api.consents.size)
        assertEquals(Triple(api.server.single().remoteId, true, "v1"), api.consents.single())

        assertTrue(sync.sync())
        assertEquals(1, api.consents.size)

        repo.setLocationConsent(id, false)
        assertTrue(sync.sync())
        assertEquals(listOf(true, false), api.consents.map { it.second })
    }

    @Test fun consentGivenBeforeLinkingIsSentAfterLinking() = runBlocking {
        val id = repo.add("Park", "IT")
        repo.setLocationConsent(id, true)
        assertEquals(0, api.consents.size)
        assertTrue(sync.sync())
        assertEquals(1, api.consents.size)
    }

    @Test fun consentIsRetriedAfterFailureButNotAfterRejection() = runBlocking {
        val id = repo.add("Park", "IT")
        sync.sync()
        repo.setLocationConsent(id, true)
        api.failConsent = true
        assertFalse(sync.sync())
        assertEquals(0, api.consents.size)
        api.failConsent = false
        assertTrue(sync.sync())
        assertEquals(1, api.consents.size)

        repo.setLocationConsent(id, true)
        api.consentRejectedFor += api.server.single().remoteId
        assertTrue(sync.sync())
        val calls = api.consents.size
        assertTrue(sync.sync())
        assertEquals(calls, api.consents.size)
    }

    @Test fun driversMissingFromServerBecomeInactiveAndComeBack() = runBlocking {
        val s = api.serverDriver("Kim", "Sales")
        sync.sync()
        api.server.clear()
        assertTrue(sync.sync())
        assertEquals(DriverStatus.INACTIVE, drivers().single().status)

        api.server += s
        assertTrue(sync.sync())
        assertEquals(DriverStatus.ACTIVE, drivers().single().status)
        assertEquals(1, drivers().size)
    }

    @Test fun serverNameAndDepartmentChangesAreApplied() = runBlocking {
        val s = api.serverDriver("Kim", "Sales")
        sync.sync()
        api.server[0] = s.copy(department = "Dev")
        assertTrue(sync.sync())
        assertEquals("Dev", drivers().single().department)
    }

    @Test fun offlineIsRetryUnauthorizedStopsAndNotLinkedDoesNothing() = runBlocking {
        repo.add("Park", "IT")
        api.online = false
        assertFalse(sync.sync())
        api.online = true; api.unauthorized = true
        assertTrue(sync.sync())
        assertEquals(0, api.registered.size)

        api.unauthorized = false; linked = false
        assertTrue(sync.sync())
        assertEquals(0, api.registered.size)
        linked = true
        assertTrue(sync.sync())
        assertEquals(1, api.registered.size)
    }

    @Test fun addingDriverOrChangingConsentRequestsSync() = runBlocking {
        pendingCalls = 0
        val id = repo.add("Park", "IT")
        assertEquals(1, pendingCalls)
        repo.setLocationConsent(id, true)
        assertEquals(2, pendingCalls)
    }
}
