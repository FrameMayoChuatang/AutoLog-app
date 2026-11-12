package com.framemayo.autolog.ui

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import androidx.lifecycle.viewModelScope
import android.app.Application
import com.framemayo.autolog.AppContainer
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import android.content.Context
import android.content.ContextWrapper
import com.framemayo.autolog.AutoLogApp
import com.framemayo.autolog.data.local.*
import com.framemayo.autolog.data.location.*
import com.framemayo.autolog.data.repository.TripRepository
import com.framemayo.autolog.service.RecorderState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PurposeSaveLifecycleTest {
    @Test fun cancellingDialogAfterPurposeCommitStillPurgesPersonalLocation() {
        val app = AutoLogApp()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = app
        }
        ReflectionHelpers.callInstanceMethod<Void>(app, "attach", ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        AutoLogApp::class.java.getDeclaredField("container").apply { isAccessible = true }.set(app, AppContainer(app))
        val vm = MainViewModel(app)
        val previousVehicle = RecorderState.snapshot.value
        RecorderState.update { it.copy(gear = VehicleGear.GEAR_PARK) }
        val cleanupAllowed = CompletableDeferred<Unit>()
        var purposeCommitted = false
        var deleted = false
        var cleanupStarted = false
        val trip = TripEntity(id = 1, driverId = null, startedAt = 1, endedAt = 2,
            startOdometerKm = 100f, lastOdometerKm = 101f, lastSavedAt = 2)
        val repository = object : TripRepository by app.container.trips {
            override suspend fun get(id: Long) = trip
            override suspend fun setPurpose(tripId: Long, purpose: TripPurpose, memo: String?, purposeText: String?) {
                assertEquals(TripPurpose.PERSONAL, purpose)
                purposeCommitted = true
            }
        }
        val dao = object : TripLocationDao {
            override suspend fun upsertIfConsented(tripId: Long, driverId: Long, latitude: Double, longitude: Double, accuracyM: Float?, capturedAt: Long) = -1L
            override suspend fun upsert(location: TripLocationEntity) = Unit
            override suspend fun get(tripId: Long): TripLocationEntity? = null
            override suspend fun deleteByDriver(driverId: Long) = Unit
            override suspend fun delete(tripId: Long) {
                cleanupStarted = true
                cleanupAllowed.await()
                deleted = true
            }
        }
        val source = object : LocationSource {
            override fun isAvailable() = false
            override suspend fun currentFix(): LocationFix? = null
        }
        // 실행 중단 시점을 제어하되 제품 코드에 테스트 전용 의존성을 추가하지 않는다.
        MainViewModel::class.java.getDeclaredField("trips").apply { isAccessible = true }.set(vm, repository)
        MainViewModel::class.java.getDeclaredField("arrival").apply { isAccessible = true }.set(vm, ArrivalLocationRecorder(dao, source))
        val dialog = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val request = dialog.launch { vm.setPurpose(1, TripPurpose.PERSONAL, null) }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(purposeCommitted)
            assertTrue(cleanupStarted)
            assertFalse(deleted)
            dialog.cancel()
            cleanupAllowed.complete(Unit)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(request.isCancelled)
            assertTrue(deleted)
        } finally {
            dialog.cancel()
            vm.viewModelScope.cancel()
            RecorderState.update { previousVehicle }
        }
    }
}
