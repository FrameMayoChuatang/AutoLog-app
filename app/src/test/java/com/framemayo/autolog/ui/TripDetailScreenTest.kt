package com.framemayo.autolog.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.framemayo.autolog.data.local.*
import com.framemayo.autolog.ui.theme.AutoLogTheme
import org.junit.Assert.assertEquals
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
class TripDetailScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val trip = TripEntity(id = 1, driverId = null, startedAt = 1000, endedAt = 61000, startOdometerKm = 100f, endOdometerKm = 112f, lastOdometerKm = 112f, lastSavedAt = 61000, status = TripStatus.COMPLETED)
    private val profiles = (1L..30).map { DriverProfile(DriverEntity(id = it, name = "원래 이름$it", department = "개발"), "운전자$it", 0) }
    private val state = MainUiState(profiles = profiles, drivers = profiles.map { it.driver }, linked = true)

    @Test fun detailShowsOverviewAndTimelineInReadingOrder() {
        val stop = TripStopEntity(tripId = 1, startedAt = 21_000, endedAt = 41_000, odometerKm = 105f)
        val charge = EnergyEventEntity(kind = EnergyEventKind.CHARGE, tripId = 1, detectedAt = 30_000, odometerKm = 105f,
            beforePercent = 40f, afterPercent = 50f, estimatedAmount = 7.7f, estimatedCostWon = 2_500)
        compose.setContent { AutoLogTheme { detail(trip.copy(driverId = 1), stops = listOf(stop), energy = listOf(charge)) } }
        compose.onNodeWithText("운전자1").assertIsDisplayed()
        compose.onNodeWithText("12.0 km · 1분").assertIsDisplayed()
        val titles = listOf("출발", "주행", "정차", "충전", "주행", "도착")
        compose.onNodeWithText("출발").performScrollTo()
        val tops = titles.mapIndexed { i, title ->
            compose.onAllNodesWithText(title, substring = false)[if (i == 4) 1 else 0].fetchSemanticsNode().positionInRoot.y
        }
        org.junit.Assert.assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("배터리 40% → 50% · 약 2,500원").performScrollTo().assertIsDisplayed()
        // 확인 버튼은 타임라인을 다 읽은 뒤에 나온다.
        scrollTo("운행 확인")
        compose.onNodeWithText("목적 선택이 필요해요").assertIsDisplayed()
        val arrival = compose.onNodeWithText("도착").fetchSemanticsNode().positionInRoot.y
        org.junit.Assert.assertTrue(compose.onNodeWithText("운행 확인").fetchSemanticsNode().positionInRoot.y > arrival)
        screenshot("trip-detail")
    }

    @Test fun backReturnsToList() {
        var back = 0
        compose.setContent { AutoLogTheme { detail(trip, onBack = { back++ }) } }
        compose.onNodeWithText("← 최근 운행으로 돌아가기").performClick()
        assertEquals(1, back)
    }

    @Test fun confirmedPurposeCanBeChangedAndCancelledWithoutSaving() {
        val saved = mutableListOf<TripPurpose>()
        compose.setContent { AutoLogTheme { detail(trip.copy(purpose = TripPurpose.PERSONAL), onPurpose = { purpose, _ -> saved += purpose }) } }
        compose.onNodeWithText("COMPLETED", substring = true).assertDoesNotExist()
        compose.onNodeWithText("완료").assertDoesNotExist()
        compose.onNodeWithText("전송 대기").assertDoesNotExist()
        scrollTo("확인 및 수정")
        compose.onNodeWithText("확인 및 수정").performClick()
        compose.onNodeWithText("업무 · 출퇴근").performClick()
        compose.onNodeWithText("닫기").performClick()
        assertEquals(emptyList<TripPurpose>(), saved)
        scrollTo("확인 및 수정")
        compose.onNodeWithText("확인 및 수정").performClick()
        compose.onNodeWithText("업무 · 출퇴근").performClick()
        compose.onNodeWithText("목적 저장").performClick()
        assertEquals(listOf(TripPurpose.BUSINESS_COMMUTE), saved)
    }

    @Test fun driverAssignmentSearchesAllProfilesAndRequiresConfirmation() {
        val assigned = mutableListOf<Long>()
        compose.setContent { AutoLogTheme { detail(trip, onAssign = { assigned += it }) } }
        scrollTo("운행 확인")
        compose.onNodeWithText("운행 확인").performClick()
        compose.onNodeWithText("목적 저장").assertIsNotEnabled()
        compose.onNodeWithText("운전자 지정").performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("30")
        compose.onNodeWithContentDescription("운전자30 검색 결과 선택").performClick()
        assertEquals(emptyList<Long>(), assigned)
        screenshot("assign-confirm")
        compose.onNodeWithText("지정").performClick()
        assertEquals(listOf(30L), assigned)
    }

    @Test fun assignedDriverCannotBeReassignedAndDrivingClosesReview() {
        val parked = mutableStateOf(true)
        compose.setContent { AutoLogTheme { detail(trip.copy(driverId = 1), parked = parked.value) } }
        compose.onNodeWithText("운전자1").assertIsDisplayed()
        scrollTo("운행 확인")
        compose.onNodeWithText("운행 확인").performClick()
        compose.onNodeWithText("운전자 지정").assertDoesNotExist()
        screenshot("trip-review")
        compose.runOnIdle { parked.value = false }
        compose.onNodeWithText("목적 저장").assertDoesNotExist()
        scrollTo("운행 확인")
        compose.onNodeWithText("운행 확인").assertIsNotEnabled()
    }

    @Test fun businessRequiresTextAndSavesCatalogChoiceSeparately() {
        var saved: Pair<TripPurpose, String?>? = null
        compose.setContent { AutoLogTheme { detail(
            trip, onPurpose = { p, text -> saved = p to text },
            loadPurposes = { listOf(PurposeEntity("visit", "거래처 방문")) },
        ) } }
        scrollTo("운행 확인")
        compose.onNodeWithText("운행 확인").performClick()
        compose.onNodeWithText("업무", substring = false).performClick()
        compose.onNodeWithText("목적 저장").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("거래처 방문", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("목적 저장").performScrollTo().performClick()
        assertEquals(TripPurpose.BUSINESS_GENERAL to "거래처 방문", saved)
    }

    @Test fun offlineCatalogAllowsDirectInputAndSaveFailureKeepsDraft() {
        compose.setContent { AutoLogTheme { detail(
            trip, onPurpose = { _, _ -> error("disk failure") }, loadPurposes = { null },
        ) } }
        scrollTo("운행 확인")
        compose.onNodeWithText("운행 확인").performClick()
        compose.onNodeWithText("업무", substring = false).performClick()
        compose.onNodeWithText("업무 목적", substring = false).performScrollTo().performTextInput("직접 입력")
        compose.onNodeWithText("목록을 불러오지 못했어요", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("목적 저장").performScrollTo().performClick()
        compose.onNodeWithText("저장하지 못했어요", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("업무 목적", substring = false).performScrollTo().assertTextContains("직접 입력")
    }

    @androidx.compose.runtime.Composable
    private fun detail(
        trip: TripEntity,
        parked: Boolean = true,
        stops: List<TripStopEntity> = emptyList(),
        energy: List<EnergyEventEntity> = emptyList(),
        onBack: () -> Unit = {},
        onAssign: (Long) -> Unit = {},
        onPurpose: suspend (TripPurpose, String?) -> Unit = { _, _ -> },
        loadPurposes: suspend () -> List<PurposeEntity>? = { emptyList() },
    ) = TripDetailScreen(trip, state, parked, stops, energy, onBack, onAssign, onPurpose, loadPurposes)

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/trip-review/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
