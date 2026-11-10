package com.framemayo.autolog.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.framemayo.autolog.data.repository.DriverRepositoryImpl
import com.framemayo.autolog.data.repository.RemoteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val A = "11111111-1111-4111-8111-111111111111"
private const val B = "22222222-2222-4222-8222-222222222222"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriverUpsertTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AutoLogDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = DriverRepositoryImpl(db.driverDao())

    @After fun close() = db.close()

    @Test
    fun insertsNewAndUpdatesExisting() = runBlocking {
        repo.upsertRemote(listOf(RemoteDriver(A, "Kim", "영업")))
        repo.upsertRemote(listOf(RemoteDriver(A, "Kim", "개발"), RemoteDriver(B, "Lee", "인사")))
        val all = repo.all.first().associateBy { it.remoteId }
        assertEquals(2, all.size)
        assertEquals("개발", all[A]?.department)
    }

    @Test
    fun failureMidwaySavesNothing() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER boom BEFORE INSERT ON drivers WHEN NEW.name = 'BOOM' BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )
        val result = runCatching { repo.upsertRemote(listOf(RemoteDriver(A, "Kim", "영업"), RemoteDriver(B, "BOOM", "x"))) }
        assertTrue(result.isFailure)
        assertEquals(0, repo.all.first().size)
    }
}
