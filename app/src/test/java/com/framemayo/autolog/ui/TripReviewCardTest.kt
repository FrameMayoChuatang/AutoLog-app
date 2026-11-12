package com.framemayo.autolog.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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
class TripReviewCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val trip = TripEntity(id = 1, driverId = null, startedAt = 1000, endedAt = 61000, startOdometerKm = 100f, endOdometerKm = 112f, lastOdometerKm = 112f, lastSavedAt = 61000, status = TripStatus.COMPLETED)
    private val profiles = (1L..30).map { DriverProfile(DriverEntity(id = it, name = "원래 이름$it", department = "개발"), "운전자$it", 0) }
    private val state = MainUiState(profiles = profiles, drivers = profiles.map { it.driver }, linked = true)

    @Test fun confirmedPurposeCanBeChangedAndCancelledWithoutSaving() {
        val saved = mutableListOf<TripPurpose>()
        compose.setContent { AutoLogTheme { TripReviewCard(trip.copy(purpose = TripPurpose.PERSONAL), state, true, {}, { saved += it }) } }
        compose.onNodeWithText("COMPLETED", substring = true).assertDoesNotExist()
        compose.onNodeWithText("완료").assertIsDisplayed()
        compose.onNodeWithText("확인 및 수정").performClick()
        compose.onNodeWithText("업무 · 출퇴근").performClick()
        compose.onNodeWithText("닫기").performClick()
        assertEquals(emptyList<TripPurpose>(), saved)
        compose.onNodeWithText("확인 및 수정").performClick()
        compose.onNodeWithText("업무 · 출퇴근").performClick()
        compose.onNodeWithText("목적 저장").performClick()
        assertEquals(listOf(TripPurpose.BUSINESS_COMMUTE), saved)
    }

    @Test fun driverAssignmentSearchesAllProfilesAndRequiresConfirmation() {
        val assigned = mutableListOf<Long>()
        compose.setContent { AutoLogTheme { TripReviewCard(trip, state, true, { assigned += it }, {}) } }
        compose.onNodeWithText("운행 확인").performClick()
        compose.onNodeWithText("목적 저장").assertIsNotEnabled()
        compose.onNodeWithText("운전자 지정").performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("30")
        compose.onNodeWithContentDescription("운전자30 검색 결과 선택").performClick()
        assertEquals(emptyList<Long>(), assigned)
        compose.onNodeWithText("지정").performClick()
        assertEquals(listOf(30L), assigned)
    }

    @Test fun assignedDriverCannotBeReassignedAndDrivingClosesReview() {
        val parked = mutableStateOf(true)
        compose.setContent { AutoLogTheme { TripReviewCard(trip.copy(driverId = 1), state, parked.value, {}, {}) } }
        compose.onNodeWithText("운전자1").assertIsDisplayed()
        compose.onNodeWithText("운행 확인").performClick()
        compose.onNodeWithText("운전자 지정").assertDoesNotExist()
        screenshot("trip-review")
        compose.runOnIdle { parked.value = false }
        compose.onNodeWithText("목적 저장").assertDoesNotExist()
        compose.onNodeWithText("운행 확인").assertIsNotEnabled()
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
