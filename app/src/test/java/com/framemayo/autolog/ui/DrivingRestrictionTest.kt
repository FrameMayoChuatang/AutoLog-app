package com.framemayo.autolog.ui

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
import java.io.File

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
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                DrivingRestrictionGate(state.value, ux.value, onRetryRestrictions = onRetry) {
                    DriverSelectionScreen(state.value.profiles, true, true, true, {}, { _, _, _, _, _ -> }, { true }, {})
                }
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

    @Test fun connectionLossKeepsReadOnlySelectionAndDoesNotClaimRecording() {
        show()
        compose.runOnIdle {
            ux.value = DrivingUxState()
            state.value = state.value.copy(vehicle = parked.copy(collector = CollectorStatus.RETRYING))
        }
        compose.onNodeWithText("운전자의 프로필을 선택하세요.").assertIsDisplayed()
        compose.onNodeWithText("운행 현황").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자 프로필 선택").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("기록 중").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자 검색 열기").assertIsNotEnabled()
    }

    @Test fun parkedOdometerFailureKeepsProfilesVisibleUntilRecovery() {
        state.value = state.value.copy(vehicle = parked.copy(collector = CollectorStatus.RETRYING, odometerKm = null))
        show()
        compose.onNodeWithText("운전자의 프로필을 선택하세요.").assertIsDisplayed()
        compose.onNodeWithText("차량 정보 수신 대기 · 차량 상태 확인 후 운행을 시작할 수 있습니다.").assertIsDisplayed()
        compose.onNodeWithText("운행 현황").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자 프로필 선택").assertIsDisplayed().assertIsNotEnabled().performClick()
        compose.onNodeWithText("주행 시작").assertDoesNotExist()
        compose.onNodeWithText("프로필 관리").assertIsNotEnabled()
        compose.onNodeWithText("최근 운행").assertIsNotEnabled()
        compose.onNodeWithContentDescription("운전자 추가").assertIsNotEnabled()
        screenshot("selection-waiting")
        compose.runOnIdle { state.value = state.value.copy(vehicle = parked) }
        compose.onNodeWithContentDescription("운전자 프로필 선택").assertIsEnabled().performClick()
        compose.onNodeWithText("주행 시작").assertIsDisplayed()
    }

    @Test fun initialUnknownStateShowsProfilesWithoutEnablingActions() {
        state.value = state.value.copy(vehicle = VehicleSnapshot())
        ux.value = DrivingUxState()
        show()
        compose.onNodeWithContentDescription("운전자 프로필 선택").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("운전자 검색 열기").assertIsNotEnabled()
        compose.onNodeWithText("운행 현황").assertDoesNotExist()
        compose.onAllNodes(hasClickAction() and isEnabled()).assertCountEquals(0)
    }

    @Test fun confirmedPlatformRestrictionStillShowsDrivingStatusWithoutActiveTrip() {
        ux.value = clearUx.copy(activeRestrictions = 64)
        show()
        compose.onNodeWithText("운행 현황").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자 프로필 선택").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun activeTripRemainsVisibleWhenParkedVehicleDataFails() {
        state.value = state.value.copy(vehicle = parked.copy(collector = CollectorStatus.RETRYING),
            activeTrip = TripEntity(id = 1, driverId = 1, startedAt = 0, endedAt = 60_000L,
                lastSavedAt = 60_000L, startOdometerKm = 100f, lastOdometerKm = 101f))
        show()
        compose.onNodeWithText("운행 현황").assertIsDisplayed()
        compose.onNodeWithText("1.0 km").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자 프로필 선택").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
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

    @Test fun waitingDashboardUsesDisplayAreaWithoutInventingTripValues() {
        state.value = state.value.copy(vehicle = parked.copy(gear = VehicleGear.GEAR_DRIVE,
            collector = CollectorStatus.RETRYING, odometerKm = null))
        show()
        compose.onNodeWithText("차량 정보 수신 대기").assertIsDisplayed()
        compose.onNodeWithText("진행 중인 운행 없음").assertIsDisplayed()
        compose.onAllNodesWithText("—").assertCountEquals(2)
        compose.onNodeWithText("0.0 km").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("driving-dashboard").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.width >= root.width * 0.9f)
        assertTrue(panel.height >= root.height * 0.65f)
        screenshot("waiting-wide")
    }

    @Test fun wideDashboardKeepsDriverAboveEqualReadableMetrics() {
        state.value = state.value.copy(vehicle = parked.copy(gear = VehicleGear.GEAR_DRIVE),
            activeTrip = TripEntity(id = 1, driverId = 1, startedAt = 0, startOdometerKm = 100f,
                lastOdometerKm = 112.4f, lastSavedAt = 25 * 60_000L, endedAt = 25 * 60_000L))
        show()
        val driver = compose.onNodeWithTag("driving-driver").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val distance = compose.onNodeWithTag("metric-주행 거리").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val time = compose.onNodeWithTag("metric-주행 시간").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(driver.bottom <= distance.top)
        assertEquals(distance.width, time.width, 1f)
        assertEquals(distance.top, time.top, 1f)
        assertTrue(compose.onNodeWithText("12.4 km").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.height >
            compose.onNodeWithText("주행 거리").fetchSemanticsNode().boundsInRoot.height * 2)
        compose.onNodeWithText("25분").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        screenshot("active-wide")
    }

    @Test fun restrictionFailureExplainsLockAndRetryDoesNotUnlockBeforeConfirmedState() {
        ux.value = DrivingUxState(failure = DrivingUxFailure.READ_FAILED)
        var retries = 0
        show { retries++ }
        compose.onNodeWithText("주행 제한 정보를 읽을 수 없음 · 입력 잠금").assertIsDisplayed()
        compose.onNodeWithText("제한 정보 다시 연결").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithContentDescription("운전자 검색 열기").assertIsNotEnabled()
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
    @Config(qualifiers = "w540dp-h420dp-land-night-mdpi")
    fun compactWaitingKeepsRecentProfilesAndRetryVisible() {
        state.value = state.value.copy(profiles = (1L..7L).map {
            DriverProfile(DriverEntity(id = it, name = "운전자$it", department = ""), "운전자$it", 0)
        })
        ux.value = DrivingUxState(failure = DrivingUxFailure.READ_FAILED)
        show()
        for (id in 1..5) compose.onNodeWithContentDescription("운전자$id 프로필 선택")
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("운전자6 프로필 선택").assertDoesNotExist()
        compose.onNodeWithText("더보기").assertDoesNotExist()
        compose.onNodeWithText("제한 정보 다시 연결").assertIsDisplayed()
        compose.onNodeWithText("주행 제한 정보를 읽을 수 없음 · 입력 잠금").assertIsDisplayed()
        screenshot("selection-waiting-compact")
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
        screenshot("restriction-compact")
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

    @Test
    @Config(qualifiers = "w800dp-h600dp-land-night-mdpi")
    fun mediumHeightKeepsValuesWholeWithRestrictionMessage() {
        ux.value = DrivingUxState(failure = DrivingUxFailure.READ_FAILED)
        state.value = state.value.copy(activeTrip = TripEntity(id = 1, driverId = 1,
            startedAt = 0, endedAt = 25 * 60_000L, lastSavedAt = 25 * 60_000L,
            startOdometerKm = 100f, lastOdometerKm = 112.4f))
        show()
        assertWholeMetric("12.4 km")
        assertWholeMetric("25분")
        compose.onNodeWithText("제한 정보 다시 연결").assertIsDisplayed()
        screenshot("medium-height")
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp-port-night-mdpi")
    fun narrowCardsFitLongValuesAndUnitsWithoutEllipsis() {
        state.value = state.value.copy(vehicle = parked.copy(gear = VehicleGear.GEAR_DRIVE),
            activeTrip = TripEntity(id = 1, driverId = 1, startedAt = 0, endedAt = 125 * 60_000L,
                lastSavedAt = 125 * 60_000L, startOdometerKm = 100f, lastOdometerKm = 12445.6f))
        show()
        assertWholeMetric("12345.6 km")
        assertWholeMetric("125분")
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        screenshot("narrow-long-values")
    }

    private fun assertWholeMetric(value: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(value).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val result = layouts.single()
        assertFalse("$value: size=${result.size}, width=${result.didOverflowWidth}, height=${result.didOverflowHeight}, lines=${result.lineCount}, style=${result.layoutInput.style}", result.hasVisualOverflow)
    }

    @Test fun largeValuesFitWideCardsWithoutDroppingUnits() {
        state.value = state.value.copy(vehicle = parked.copy(gear = VehicleGear.GEAR_DRIVE),
            activeTrip = TripEntity(id = 1, driverId = 1, startedAt = 0, endedAt = 125 * 60_000L,
                lastSavedAt = 125 * 60_000L, startOdometerKm = 100f, lastOdometerKm = 12445.6f))
        show()
        assertWholeMetric("12345.6 km")
        assertWholeMetric("125분")
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/driving-dashboard/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
