package com.framemayo.autolog.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.framemayo.autolog.data.local.AutoLogDatabase
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.TripDao
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.local.TripStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripConcurrentUpdateTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AutoLogDatabase::class.java)
        .allowMainThreadQueries().build()
    private val real = db.tripDao()

    private var ackAfterRead: (suspend (TripEntity) -> Unit)? = null
    private val racingDao = object : TripDao by real {
        override suspend fun get(id: Long): TripEntity? = real.get(id)?.also { t -> ackAfterRead?.let { ackAfterRead = null; it(t) } }
        override suspend fun active(): TripEntity? = real.active()?.also { t -> ackAfterRead?.let { ackAfterRead = null; it(t) } }
    }
    private var now = 1_000L
    private val repo = TripRepositoryImpl(racingDao, clock = { now++ })

    @After fun close() = db.close()

    private fun ack(revision: Long, serverVersion: Long): suspend (TripEntity) -> Unit =
        { t -> real.recordAck(t.id, revision, serverVersion); real.recordConfirm(t.id, serverVersion, t.updatedAt) }

    private suspend fun endedTrip(driverId: Long? = null): TripEntity {
        val t = repo.start(driverId, 100f)
        return repo.end(110f)!!.also { assertEquals(t.id, it.id) }
    }

    @Test fun setPurposeKeepsConcurrentAck() = runBlocking {
        val t = endedTrip()
        ackAfterRead = ack(revision = t.sourceRevision, serverVersion = 7)
        repo.setPurpose(t.id, TripPurpose.PERSONAL)
        val saved = real.get(t.id)!!
        assertEquals(TripPurpose.PERSONAL, saved.purpose)
        assertEquals(t.sourceRevision, saved.ackedRevision)
        assertEquals(7, saved.serverVersion)
    }

    @Test fun setDriverKeepsConcurrentAck() = runBlocking {
        val driverId = db.driverDao().insert(DriverEntity(name = "김운전", department = "영업팀"))
        val t = endedTrip()
        ackAfterRead = ack(revision = t.sourceRevision, serverVersion = 9)
        assertTrue(repo.setDriver(t.id, driverId))
        val saved = real.get(t.id)!!
        assertEquals(driverId, saved.driverId)
        assertEquals(t.sourceRevision + 1, saved.sourceRevision)
        assertEquals(t.sourceRevision, saved.ackedRevision)
        assertEquals(9, saved.serverVersion)
    }

    @Test fun endKeepsConcurrentAckOfRunningUpload() = runBlocking {
        val t = repo.start(null, 100f)
        ackAfterRead = ack(revision = t.sourceRevision, serverVersion = 3)
        val ended = repo.end(112f)!!
        assertEquals(TripStatus.COMPLETED, ended.status)
        assertEquals(112f, ended.endOdometerKm)
        assertEquals(t.sourceRevision + 1, ended.sourceRevision)
        assertEquals(t.sourceRevision, ended.ackedRevision)
        assertEquals(3, ended.serverVersion)
    }
}
