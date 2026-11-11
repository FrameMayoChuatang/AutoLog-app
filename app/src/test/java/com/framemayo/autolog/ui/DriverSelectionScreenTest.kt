package com.framemayo.autolog.ui

import android.app.Application
import android.graphics.Canvas
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 34는 Android 12 Skia를 사용하므로 child RuntimeShader 검증은 SDK 35에서 실행한다.
@Config(sdk = [35], application = Application::class, qualifiers = "w1100dp-h900dp-land-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverSelectionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val profiles = (1L..7).map { DriverProfile(DriverEntity(id = it, name = "운전자$it", department = ""), "운전자$it", ((it - 1) % 5).toInt()) }

    private fun show(parked: Boolean = true, linked: Boolean = true, ready: Boolean = true, onStart: (Long) -> Unit = {}) {
        compose.setContent { DriverSelectionScreen(profiles, parked, linked, ready, onStart, { _, _, _ -> }, { true }, {}) }
    }

    @Test fun selectingAProfileStartsImmediately() {
        val starts = mutableListOf<Long>()
        show(onStart = { starts.add(it) })
        compose.onNodeWithContentDescription("운전자2 프로필로 운행 시작").performClick()
        assertEquals(listOf(2L), starts)
        compose.onNodeWithText("운행 시작", substring = false).assertDoesNotExist()
        screenshot("selection-native")
    }

    @Test fun searchStaysAtTopRightAndOtherProfilesStayBelowRecent() {
        show()
        val recent = compose.onNodeWithContentDescription("운전자5 프로필로 운행 시작").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithContentDescription("운전자 이름 검색").fetchSemanticsNode().boundsInRoot
        val other = compose.onNodeWithContentDescription("운전자6 프로필로 운행 시작").fetchSemanticsNode().boundsInRoot
        val manage = compose.onNodeWithText("프로필 관리").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(search.bottom < recent.top)
        org.junit.Assert.assertTrue(search.left > manage.right)
        org.junit.Assert.assertTrue(manage.center.y in search.top..search.bottom)
        org.junit.Assert.assertTrue(other.top > recent.bottom)
    }

    @Test fun searchFiltersLowerListAndKeepsRecentProfiles() {
        show()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("7")
        compose.onNodeWithContentDescription("운전자7 프로필로 운행 시작").assertExists()
        compose.onNodeWithContentDescription("운전자6 프로필로 운행 시작").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자1 프로필로 운행 시작").assertExists()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextReplacement("없는이름")
        compose.onNodeWithText("검색 결과가 없습니다.").assertExists()
    }

    @Test fun queryAndManagementModeSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DriverSelectionScreen(profiles, true, true, true, {}, { _, _, _ -> }, { true }, {}) }
        compose.onNodeWithText("프로필 관리", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("7")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("완료").assertExists()
        compose.onNodeWithContentDescription("운전자7 프로필 수정").assertExists()
        compose.onNodeWithContentDescription("운전자6 프로필 수정").assertDoesNotExist()
    }

    @Test fun unlinkedVehicleCannotStart() {
        val starts = mutableListOf<Long>()
        show(linked = false, onStart = { starts.add(it) })
        compose.onNodeWithContentDescription("운전자1 프로필로 운행 시작").performClick()
        compose.onNodeWithText("차량 연결이 필요합니다").assertIsDisplayed()
        assertEquals(emptyList<Long>(), starts)
    }

    @Test fun missingOdometerCannotStart() {
        val starts = mutableListOf<Long>()
        show(ready = false, onStart = { starts.add(it) })
        compose.onNodeWithContentDescription("운전자1 프로필로 운행 시작").performClick()
        compose.onNodeWithText("주행거리 수신 대기").assertIsDisplayed()
        assertEquals(emptyList<Long>(), starts)
    }

    @Test fun unknownOrNonParkGearHidesControls() {
        show(parked = false)
        compose.onNodeWithText("주차 후 이용해 주세요").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자 추가").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자 이름 검색").assertDoesNotExist()
    }

    @Test fun leavingParkClosesEditor() {
        val parked = mutableStateOf(true)
        compose.setContent { DriverSelectionScreen(profiles, parked.value, true, true, {}, { _, _, _ -> }, { true }, {}) }
        compose.onNodeWithContentDescription("운전자 추가").performClick()
        compose.onNodeWithContentDescription("운전자 이름").assertIsDisplayed()
        compose.runOnIdle { parked.value = false }
        compose.onNodeWithContentDescription("운전자 이름").assertDoesNotExist()
        compose.onNodeWithText("주차 후 이용해 주세요").assertIsDisplayed()
    }

    @Test fun createProfileValidatesNameAndSavesChosenColor() {
        val saved = mutableListOf<Pair<String, Int>>()
        compose.setContent { DriverSelectionScreen(emptyList(), true, true, true, {}, { _, name, color -> saved.add(name to color) }, { true }, {}) }
        compose.onNodeWithContentDescription("운전자 추가").performClick()
        compose.onNodeWithText("저장").assertIsNotEnabled()
        compose.onNodeWithContentDescription("운전자 이름").performTextInput("  홍길동  ")
        compose.onNodeWithContentDescription("분홍").performClick()
        compose.onNodeWithContentDescription("기본 구름 캐릭터").assertIsNotSelected()
        compose.onNodeWithText("홍길동", substring = false).assertExists()
        screenshot("editor-native")
        compose.onNodeWithText("저장").performClick()
        compose.waitForIdle()
        assertEquals(listOf("홍길동" to 2), saved)
    }

    @Test fun presetRestoresBlueAndEditingPreservesIdentity() {
        val saved = mutableListOf<Triple<Long?, String, Int>>()
        compose.setContent { DriverSelectionScreen(profiles, true, true, true, {}, { id, name, color -> saved.add(Triple(id, name, color)) }, { true }, {}) }
        compose.onNodeWithText("프로필 관리", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("운전자2 프로필 수정").performClick()
        compose.onNodeWithContentDescription("운전자 이름").performTextReplacement("수정 이름")
        compose.onNodeWithContentDescription("기본 구름 캐릭터").performClick()
        compose.onNodeWithContentDescription("하늘").assertIsSelected()
        compose.onNodeWithText("저장").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple(2L, "수정 이름", 0)), saved)
    }

    @Test fun deleteNeedsConfirmationAndCancelDoesNothing() {
        val deleted = mutableListOf<Long>()
        compose.setContent { DriverSelectionScreen(profiles, true, true, true, {}, { _, _, _ -> }, { deleted.add(it); true }, {}) }
        compose.onNodeWithText("프로필 관리", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("운전자1 프로필 삭제").performClick()
        compose.onNodeWithText("취소").performClick()
        assertEquals(emptyList<Long>(), deleted)
        compose.onNodeWithContentDescription("운전자1 프로필 삭제").performClick()
        compose.onNodeWithText("삭제", substring = false).performClick()
        compose.waitForIdle()
        assertEquals(listOf(1L), deleted)
    }

    @Test fun failedSaveKeepsEditorOpen() {
        compose.setContent { DriverSelectionScreen(profiles, true, true, true, {}, { _, _, _ -> error("storage unavailable") }, { true }, {}) }
        compose.onNodeWithContentDescription("운전자 추가").performClick()
        compose.onNodeWithContentDescription("운전자 이름").performTextInput("이름")
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("저장하지 못했습니다. 다시 시도해 주세요.").assertExists()
        compose.onNodeWithText("저장").assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w540dp-h900dp-port-notnight-mdpi")
    fun splitScreenSupportsScrollingAndEditing() {
        show()
        compose.onNodeWithContentDescription("운전자 추가").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름").performTextInput("분할 화면")
        compose.onNodeWithText("저장").assertIsDisplayed().assertIsEnabled()
        screenshot("editor-split")
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/driver-selection/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
