package com.framemayo.autolog.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.framemayo.autolog.data.local.DriverEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1100dp-h760dp-land-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverSelectionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val drivers = listOf(
        DriverEntity(id = 1, name = "김민수", department = "영업팀"),
        DriverEntity(id = 2, name = "이서연", department = "운영팀"),
        DriverEntity(id = 3, name = "박지훈", department = "경영지원팀"),
    )

    private fun show(
        linked: Boolean = true,
        odometerReady: Boolean = true,
        parked: Boolean = true,
        onStart: (Long) -> Unit = {},
    ) {
        compose.setContent {
            DriverSelectionScreen(drivers, 1, parked, linked, odometerReady, onStart, { _, _ -> }, {})
        }
    }

    @Test fun selectionRequiresAnExplicitStartAndSurvivesRecreation() {
        val starts = mutableListOf<Long>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DriverSelectionScreen(drivers, 1, true, true, true, { starts.add(it) }, { _, _ -> }, {})
        }
        compose.onNodeWithText("운행 시작").assertIsNotEnabled()
        compose.onNodeWithText("이서연").performClick()
        assertEquals(emptyList<Long>(), starts)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("이서연님으로 운행을 기록합니다.").assertIsDisplayed()
        compose.onNodeWithText("운행 시작").assertIsEnabled().performClick()
        assertEquals(listOf(2L), starts)
        screenshot("night-selected")
    }

    @Test fun searchFiltersDriversAndReportsNoResults() {
        show()
        compose.onNodeWithText("운전자 검색").performClick()
        compose.onNodeWithText("운전자 이름").performTextInput("서연")
        compose.onNodeWithText("이서연").assertIsDisplayed()
        compose.onNodeWithText("김민수").assertDoesNotExist()
        compose.onNodeWithText("운전자 이름").performTextInput("없는이름")
        compose.onNodeWithText("검색한 운전자가 없어요. 이름을 확인해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
        compose.onNodeWithText("김민수").assertIsDisplayed()
    }

    @Test fun unlinkedVehicleCannotStartWithASelectedDriver() {
        show(linked = false)
        compose.onNodeWithText("김민수").performClick()
        compose.onNodeWithText("운행 시작").assertIsNotEnabled()
        compose.onNodeWithText("운행을 시작하려면 차량을 먼저 연결해 주세요.").assertIsDisplayed()
    }

    @Test fun missingOdometerCannotStartWithASelectedDriver() {
        show(odometerReady = false)
        compose.onNodeWithText("김민수").performClick()
        compose.onNodeWithText("운행 시작").assertIsNotEnabled()
    }

    @Test fun unknownOrNonParkGearHidesDriverControls() {
        show(parked = false)
        compose.onNodeWithText("주차 후 이용해 주세요").assertIsDisplayed()
        compose.onNodeWithText("김민수").assertDoesNotExist()
        compose.onNodeWithText("운행 시작").assertIsNotEnabled()
    }

    @Test fun leavingParkDismissesInputAndBlocksSelection() {
        val parked = mutableStateOf(true)
        compose.setContent {
            DriverSelectionScreen(drivers, 1, parked.value, true, true, {}, { _, _ -> }, {})
        }
        compose.onNodeWithContentDescription("운전자 추가").performClick()
        compose.onNodeWithText("이름").assertIsDisplayed()
        compose.runOnIdle { parked.value = false }
        compose.onNodeWithText("이름").assertDoesNotExist()
        compose.onNodeWithText("운행 시작").assertIsNotEnabled()
        compose.onNodeWithText("주차 후 이용해 주세요").assertIsDisplayed()
    }

    @Test fun emptyListSupportsAddingANamedDriver() {
        val added = mutableListOf<Pair<String, String>>()
        compose.setContent {
            DriverSelectionScreen(emptyList(), null, true, true, true, {}, { name, dept -> added.add(name to dept) }, {})
        }
        compose.onNodeWithText("등록된 운전자가 없어요. 운전자를 추가해 주세요.").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자 추가").performClick()
        compose.onNodeWithText("추가").assertIsNotEnabled()
        compose.onNodeWithText("이름").performTextInput("  홍길동  ")
        compose.onNodeWithText("부서 (선택)").performTextInput("운영팀")
        compose.onNodeWithText("추가").performClick()
        assertEquals(listOf("홍길동" to "운영팀"), added)
    }

    @Test
    @Config(qualifiers = "w540dp-h900dp-port-notnight-mdpi")
    fun splitScreenKeepsSelectionAndStartVisibleInDayMode() {
        show()
        compose.onNodeWithText("김민수").assertIsDisplayed().performClick()
        compose.onNodeWithText("운행 시작").assertIsDisplayed().assertIsEnabled()
        screenshot("day-split")
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val target = File("build/reports/driver-selection/$name.png")
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
