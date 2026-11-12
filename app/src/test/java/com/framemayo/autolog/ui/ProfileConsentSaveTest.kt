package com.framemayo.autolog.ui

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.framemayo.autolog.AppContainer
import com.framemayo.autolog.AutoLogApp
import com.framemayo.autolog.data.local.*
import com.framemayo.autolog.data.location.*
import com.framemayo.autolog.data.repository.DriverRepository
import com.framemayo.autolog.service.RecorderState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileConsentSaveTest {
    @Test fun closingEditorDoesNotCancelWithdrawalCleanup() = verifyWithdrawal(cancelEditor = true, failFirst = false)
    @Test fun cleanupFailureIsReportedAndSavingAgainRetriesIt() = verifyWithdrawal(cancelEditor = false, failFirst = true)

    private fun verifyWithdrawal(cancelEditor: Boolean, failFirst: Boolean) {
        val app = AutoLogApp()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = app
        }
        ReflectionHelpers.callInstanceMethod<Void>(app, "attach", ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        AutoLogApp::class.java.getDeclaredField("container").apply { isAccessible = true }.set(app, AppContainer(app))
        val vm = MainViewModel(app)
        val previous = RecorderState.snapshot.value
        RecorderState.update { it.copy(gear = VehicleGear.GEAR_PARK) }
        var savedConsent: Boolean? = true
        var deletionCalls = 0
        var deleted = false
        val allowDelete = CompletableDeferred<Unit>()
        val drivers = object : DriverRepository by app.container.drivers {
            override suspend fun saveProfileWithConsent(id: Long?, name: String, color: Int, expression: Int, consent: Boolean): Long {
                assertEquals(7L, id)
                savedConsent = consent
                return 7L
            }
        }
        val dao = object : TripLocationDao {
            override suspend fun upsert(location: TripLocationEntity) = Unit
            override suspend fun get(tripId: Long): TripLocationEntity? = null
            override suspend fun delete(tripId: Long) = Unit
            override suspend fun deleteByDriver(driverId: Long) {
                assertEquals(7L, driverId)
                deletionCalls++
                allowDelete.await()
                if (failFirst && deletionCalls == 1) error("위치 삭제 실패")
                deleted = true
            }
        }
        val source = object : LocationSource {
            override fun isAvailable() = false
            override suspend fun currentFix(): LocationFix? = null
        }
        MainViewModel::class.java.getDeclaredField("drivers").apply { isAccessible = true }.set(vm, drivers)
        MainViewModel::class.java.getDeclaredField("arrival").apply { isAccessible = true }.set(vm, ArrivalLocationRecorder(dao, source))
        val editor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var failed = false
        try {
            val job = editor.launch { failed = runCatching { vm.saveProfile(7, "운전자", 0, 0, false) }.isFailure }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(false, savedConsent)
            assertEquals(1, deletionCalls)
            assertFalse(deleted)
            if (cancelEditor) editor.cancel()
            allowDelete.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            if (cancelEditor) {
                assertTrue(job.isCancelled)
                assertTrue(deleted)
            } else {
                assertTrue(failed)
                assertFalse(deleted)
                editor.launch { vm.saveProfile(7, "운전자", 0, 0, false) }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(2, deletionCalls)
                assertTrue(deleted)
            }
        } finally {
            editor.cancel()
            vm.viewModelScope.cancel()
            RecorderState.update { previous }
        }
    }
}
