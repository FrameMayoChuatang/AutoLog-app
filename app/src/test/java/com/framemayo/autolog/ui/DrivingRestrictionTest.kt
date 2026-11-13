package com.framemayo.autolog.ui

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverProfile
import com.framemayo.autolog.data.local.TripEntity
import com.framemayo.autolog.service.CollectorStatus
import com.framemayo.autolog.service.VehicleSnapshot
import com.framemayo.autolog.ui.theme.AutoLogTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1100dp-h900dp-land-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrivingRestrictionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 25분 30초 전에 시작한 운행(#147: 화면 시간은 현재 시각 기준). */
    private val startedAt1 = System.currentTimeMillis() - 90_000L
    private val startedAt25 = System.currentTimeMillis() - (25 * 60_000L + 30_000L)
    private val parked = VehicleSnapshot(serviceRunning = true, collector = CollectorStatus.RUNNING,
        gear = VehicleGear.GEAR_PARK, odometerKm = 120f)
    private val clearUx = DrivingUxState(ready = true, requiresOptimization = false)
    private val profile = DriverProfile(DriverEntity(id = 1, name = "운전자", department = ""), "운전자", 0)
    private val state = mutableStateOf(MainUiState(vehicle = parked, profiles = listOf(profile), linked = true))
    private val ux = mutableStateOf(clearUx)

    private fun show(onRetry: () -> Unit = {}) = compose.setContent {
        AutoLogTheme {
            DrivingRestrictionGate(state.value, ux.value, onRetryRestrictions = onRetry) {
                DriverSelectionScreen(state.value.profiles, true, true, true, {}, { _, _, _, _, _ -> }, { true }, {})
            }
        }
    }

    @Test fun inputRequiresConfirmedParkAndUnrestrictedPlatform() {
        assertTrue(canUseParkedControls(parked, clearUx))
        for (gear in listOf(null, VehicleGear.GEAR_UNKNOWN, VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_NEUTRAL)) {
            assertFalse(canUseParkedControls(parked.copy(gear = gear), clearUx))
        }
        assertFalse(canUseParkedControls(parked, DrivingUxState()))
        assertFalse(canUseParkedControls(parked, clearUx.copy(requiresOptimization = true)))
        for (flag in listOf(1, 2, 4, 8, 16, 32, 64, 128, 256)) {
            assertFalse(canUseParkedControls(parked, clearUx.copy(activeRestrictions = flag)))
        }
        assertFalse(canUseParkedControls(parked.copy(serviceRunning = false), clearUx))
        assertFalse(canUseParkedControls(parked.copy(collector = CollectorStatus.RETRYING), clearUx))
    }

    @Test fun driveClosesSearchAndReturningToParkDoesNotRestoreIt() {
        show()
        compose.onNodeWithContentDescription("운전자 검색 열기").performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("운")
        compose.runOnIdle { state.value = state.value.copy(vehicle = parked.copy(gear = VehicleGear.GEAR_DRIVE)) }
        compose.onNodeWithText("운행 현황").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자 이름 검색").assertDoesNotExist()
        compose.onNodeWithText("프로필 관리").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(vehicle = parked) }
        compose.onNodeWithContentDescription("운전자 검색 열기").assertIsDisplayed()
        compose.onNodeWithContentDescription("검색 닫기").assertDoesNotExist()
    }

    @Test fun platformRestrictionClosesProfileEditorEvenInPark() {
        show()
        compose.onNodeWithContentDescription("운전자 추가").performClick()
        compose.onNodeWithText("프로필 생성").assertIsDisplayed()
        compose.runOnIdle { ux.value = clearUx.copy(activeRestrictions = 64) }
        compose.onNodeWithText("프로필 생성").assertDoesNotExist()
        compose.onNodeWithText("운행 현황").assertIsDisplayed()
        compose.runOnIdle { ux.value = clearUx }
        compose.onNodeWithText("프로필 생성").assertDoesNotExist()
        compose.onNodeWithText("운전자 추가").assertIsDisplayed()
    }

    @Test fun connectionLossLocksInputAndDoesNotClaimRecording() {
        show()
        compose.runOnIdle {
            ux.value = DrivingUxState()
            state.value = state.value.copy(vehicle = parked.copy(collector = CollectorStatus.RETRYING))
        }
        compose.onNodeWithText("운행 현황").assertIsDisplayed()
        compose.onNodeWithText("차량 정보 수신 대기").assertIsDisplayed()
        compose.onNodeWithText("기록 중").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자 검색 열기").assertDoesNotExist()
    }

    @Test fun activeTripDisplaysValuesWithoutActions() {
        state.value = state.value.copy(vehicle = parked.copy(gear = VehicleGear.GEAR_DRIVE),
            activeTrip = TripEntity(id = 1, driverId = 1, startedAt = startedAt25, startOdometerKm = 100f,
                lastOdometerKm = 112.4f, lastSavedAt = startedAt25 + 20 * 60_000L))
        show()
        compose.onNodeWithText("기록 중").assertIsDisplayed()
        compose.onNodeWithText("운전자").assertIsDisplayed()
        compose.onNodeWithContentDescription("현재 운전자 프로필").assertIsDisplayed()
        compose.onNodeWithText("검색과 편집은 주차 후 이용해 주세요.").assertDoesNotExist()
        compose.onNodeWithText("12.4 km").assertIsDisplayed()
        compose.onNodeWithText("25분").assertIsDisplayed()
        compose.onNodeWithText("운행 종료").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun restrictionFailureExplainsLockAndRetryDoesNotUnlockBeforeConfirmedState() {
        ux.value = DrivingUxState(failure = DrivingUxFailure.READ_FAILED)
        var retries = 0
        show { retries++ }
        compose.onNodeWithText("주행 제한 정보를 읽을 수 없음 · 입력 잠금").assertIsDisplayed()
        compose.onNodeWithText("제한 정보 다시 연결").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithContentDescription("운전자 검색 열기").assertDoesNotExist()
        compose.runOnIdle { ux.value = clearUx }
        compose.onNodeWithContentDescription("운전자 검색 열기").assertIsDisplayed()
        compose.onNodeWithText("제한 정보 다시 연결").assertDoesNotExist()
    }

    @Test fun unavailableApiAndDisconnectedServiceHaveDistinctReasonsAndNoDrivingAction() {
        ux.value = DrivingUxState(failure = DrivingUxFailure.API_UNAVAILABLE)
        show()
        compose.onNodeWithText("주행 제한 API 미지원 · 입력 잠금").assertIsDisplayed()
        compose.runOnIdle {
            ux.value = DrivingUxState(failure = DrivingUxFailure.DISCONNECTED)
            state.value = state.value.copy(vehicle = parked.copy(gear = VehicleGear.GEAR_DRIVE))
        }
        compose.onNodeWithText("주행 제한 서비스 연결 끊김 · 입력 잠금").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-night-mdpi")
    fun compactRestrictionFailureKeepsRetryAndTripValuesVisible() {
        ux.value = DrivingUxState(failure = DrivingUxFailure.READ_FAILED)
        state.value = state.value.copy(activeTrip = TripEntity(id = 1, driverId = 1,
            startedAt = startedAt1, startOdometerKm = 100f, lastOdometerKm = 101f, lastSavedAt = startedAt1 + 30_000L))
        show()
        compose.onNodeWithText("제한 정보 다시 연결").assertIsDisplayed()
        compose.onNodeWithText("주행 제한 정보를 읽을 수 없음 · 입력 잠금").assertIsDisplayed()
        compose.onNodeWithText("1.0 km").assertIsDisplayed()
        compose.onNodeWithText("1분").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-night-mdpi")
    fun compactDisplayKeepsStatusAndValuesVisible() {
        state.value = state.value.copy(vehicle = parked.copy(gear = VehicleGear.GEAR_DRIVE),
            activeTrip = TripEntity(id = 1, driverId = null, startedAt = 0, startOdometerKm = 100f,
                lastOdometerKm = 101f, lastSavedAt = 60_000L))
        show()
        compose.onNodeWithText("운행 현황").assertIsDisplayed()
        compose.onNodeWithText("기록 중").assertIsDisplayed()
        compose.onNodeWithContentDescription("미지정 운전자 프로필").assertIsDisplayed()
        compose.onNodeWithText("주행 거리").assertIsDisplayed()
        compose.onNodeWithText("주행 시간").assertIsDisplayed()
        compose.onNodeWithText("검색과 편집은 주차 후 이용해 주세요.").assertDoesNotExist()
    }
}
