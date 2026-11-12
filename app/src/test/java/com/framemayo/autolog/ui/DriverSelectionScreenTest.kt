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
        compose.setContent { DriverSelectionScreen(profiles, parked, linked, ready, onStart, { _, _, _, _ -> }, { true }, {}) }
    }

    @Test fun unlinkedVehicleOnlyOffersPairingAfterProfileSelection() {
        var opened = 0
        var started = false
        compose.setContent { DriverSelectionScreen(profiles, true, false, true, { started = true }, { _, _, _, _ -> }, { true }, { opened++ }) }
        compose.onNodeWithText("차량 연결하기").assertDoesNotExist()
        compose.onNodeWithText("운행 기록 · 차량").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자1 프로필로 운행 시작").performClick()
        compose.onNodeWithText("차량 연결이 필요합니다").assertIsDisplayed()
        compose.onNodeWithText("차량 연결하기").performClick()
        assertEquals(1, opened)
        assertEquals(false, started)
    }

    @Test fun selectionNeverOffersDeferredStart() {
        val visibleProfiles = mutableStateOf(emptyList<DriverProfile>())
        compose.setContent { DriverSelectionScreen(visibleProfiles.value, true, true, true, {}, { _, _, _, _ -> }, { true }, {}) }
        compose.onNodeWithText("운전자는 나중에 선택").assertDoesNotExist()
        compose.runOnIdle { visibleProfiles.value = profiles }
        compose.onNodeWithText("운전자는 나중에 선택").assertDoesNotExist()
        compose.onNodeWithText("프로필 관리").performClick()
        compose.onNodeWithText("운전자는 나중에 선택").assertDoesNotExist()
        compose.onNodeWithText("완료").performClick()
        compose.onNodeWithText("운전자는 나중에 선택").assertDoesNotExist()
    }

    @Test fun pendingServerDriverKeepsApprovalLabel() {
        val pending = profiles.take(1).map { it.copy(driver = it.driver.copy(status = com.framemayo.autolog.data.local.DriverStatus.PENDING)) }
        compose.setContent { DriverSelectionScreen(pending, true, true, true, {}, { _, _, _, _ -> }, { true }, {}) }
        compose.onNodeWithText("승인 대기").assertIsDisplayed()
    }

    @Test fun selectingAProfileStartsImmediately() {
        val starts = mutableListOf<Long>()
        show(onStart = { starts.add(it) })
        compose.onNodeWithContentDescription("운전자2 프로필로 운행 시작").performClick()
        assertEquals(listOf(2L), starts)
        compose.onNodeWithText("운행 시작", substring = false).assertDoesNotExist()
        screenshot("selection-native")
    }

    @Test fun moreButtonDisappearsAfterRevealingExtraProfiles() {
        val starts = mutableListOf<Long>()
        show(onStart = { starts.add(it) })
        compose.onNodeWithContentDescription("운전자5 프로필로 운행 시작").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자6 프로필로 운행 시작").assertDoesNotExist()
        compose.onNodeWithText("더보기").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("운전자6 프로필로 운행 시작").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(6L), starts)
        compose.onNodeWithText("접기").assertDoesNotExist()
        compose.onNodeWithText("더보기").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자6 프로필로 운행 시작").assertExists()
        compose.onNodeWithContentDescription("운전자5 프로필로 운행 시작").performScrollTo().assertIsDisplayed()
    }

    @Test fun moreButtonOnlyAppearsWhenThereAreExtraProfiles() {
        val visibleProfiles = mutableStateOf(emptyList<DriverProfile>())
        compose.setContent { DriverSelectionScreen(visibleProfiles.value, true, true, true, {}, { _, _, _, _ -> }, { true }, {}) }
        compose.onNodeWithText("더보기").assertDoesNotExist()
        compose.runOnIdle { visibleProfiles.value = profiles.take(5) }
        compose.onNodeWithText("더보기").assertDoesNotExist()
        compose.runOnIdle { visibleProfiles.value = profiles.take(6) }
        compose.onNodeWithText("더보기").assertIsDisplayed()
    }

    @Test fun searchIsCenteredAboveHeadingAndOtherProfilesStayBelowRecent() {
        show()
        compose.onNodeWithText("더보기").performClick()
        val recent = compose.onNodeWithContentDescription("운전자5 프로필로 운행 시작").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithContentDescription("운전자 검색 열기").fetchSemanticsNode().boundsInRoot
        val other = compose.onNodeWithContentDescription("운전자6 프로필로 운행 시작").fetchSemanticsNode().boundsInRoot
        val heading = compose.onNodeWithText("운전자의 프로필을 선택하세요.").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(search.bottom <= heading.top)
        assertEquals(heading.center.x, search.center.x, 1f)
        org.junit.Assert.assertTrue(heading.bottom < recent.top)
        org.junit.Assert.assertTrue(other.top > recent.bottom)
    }

    @Test fun topBarStaysFixedAndInteractiveAfterScrollingProfiles() {
        val manyProfiles = (1L..30).map {
            DriverProfile(DriverEntity(id = it, name = "운전자$it", department = ""), "운전자$it", 0)
        }
        compose.setContent { DriverSelectionScreen(manyProfiles, true, true, true, {}, { _, _, _, _ -> }, { true }, {}) }
        compose.onNodeWithText("더보기").performClick()
        val manageBefore = compose.onNodeWithText("프로필 관리").fetchSemanticsNode().boundsInRoot
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("운전자30 프로필로 운행 시작"))
        compose.onNodeWithContentDescription("운전자30 프로필로 운행 시작").assertIsDisplayed()
        assertEquals(manageBefore, compose.onNodeWithText("프로필 관리").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("프로필 관리").performClick()
        compose.onNodeWithText("완료").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자30 프로필 수정").assertIsDisplayed()
    }

    @Test fun searchTransitionExpandsFieldWithoutSquashingSearchIcon() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DriverSearchDialog("", {}, profiles, androidx.compose.ui.geometry.Rect(300f, 300f, 780f, 364f), false, {}, {})
        }
        compose.waitForIdle()
        val widths = mutableListOf<Float>()
        var initialIconWidth: Float? = null
        repeat(6) {
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
            val icon = compose.onNodeWithContentDescription("운전자 검색", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val field = compose.onNodeWithContentDescription("운전자 이름 검색").fetchSemanticsNode().boundsInRoot
            assertEquals(icon.height, icon.width, 1f)
            initialIconWidth?.let { width -> assertEquals(width, icon.width, 1f) }
            initialIconWidth = icon.width
            widths.add(field.width)
        }
        org.junit.Assert.assertTrue(widths.first() < widths.last())
        widths.zipWithNext().forEach { (before, after) -> org.junit.Assert.assertTrue(after >= before) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun searchOnlyFiltersDialogAndClosingClearsQuery() {
        show()
        compose.onNodeWithText("더보기").performClick()
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").assertIsDisplayed().performTextInput("7")
        compose.onNodeWithContentDescription("운전자7 검색 결과 선택").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자6 검색 결과 선택").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자6 프로필로 운행 시작").assertExists()
        compose.onNodeWithContentDescription("검색 닫기").performClick()
        compose.onNodeWithContentDescription("운전자 검색 열기").assertTextContains("운전자 이름 검색...")
        compose.onNodeWithContentDescription("운전자1 프로필로 운행 시작").assertExists()
        compose.onNodeWithContentDescription("운전자6 프로필로 운행 시작").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").assertTextContains("").performTextReplacement("없는이름")
        compose.onNodeWithText("검색 결과 0명").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자7 검색 결과 선택").assertDoesNotExist()
    }

    @Test fun backdropFirstEndsInputThenClosesDialogAndClearsQuery() {
        val starts = mutableListOf<Long>()
        show(onStart = { starts.add(it) })
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("7")
        compose.onNodeWithContentDescription("검색 모달 배경").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, 4f)) }
        compose.onNodeWithContentDescription("운전자 이름 검색").assertIsDisplayed().assertIsNotFocused().assertTextContains("7")
        compose.onNodeWithContentDescription("운전자7 검색 결과 선택").assertIsDisplayed()
        compose.onNodeWithContentDescription("검색 모달 배경").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, 4f)) }
        compose.onNodeWithContentDescription("운전자 이름 검색").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자 검색 열기").assertTextContains("운전자 이름 검색...")
        assertEquals(emptyList<Long>(), starts)
    }

    @Test fun tappingDialogBodyClearsFocusWithoutClosingSearch() {
        show()
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("7")
        compose.onNodeWithContentDescription("운전자 이름 검색").assertIsFocused()
        compose.onNodeWithText("검색 결과 1명", substring = false).performTouchInput { click() }
        compose.onNodeWithContentDescription("운전자 이름 검색").assertIsDisplayed().assertIsNotFocused().assertTextContains("7")
        compose.onNodeWithContentDescription("운전자 이름 검색").performTouchInput { click() }
        compose.onNodeWithContentDescription("운전자 이름 검색").assertIsFocused()
        compose.onNodeWithContentDescription("운전자7 검색 결과 선택").assertIsDisplayed()
    }

    @Test fun choosingSearchResultStartsSelectedDriver() {
        val starts = mutableListOf<Long>()
        show(onStart = { starts.add(it) })
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("7")
        compose.onNodeWithContentDescription("운전자7 검색 결과 선택").performClick()
        assertEquals(listOf(7L), starts)
        compose.onNodeWithContentDescription("운전자 이름 검색").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자 검색 열기").assertTextContains("운전자 이름 검색...")
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").assertTextContains("")
        compose.onNodeWithContentDescription("운전자1 검색 결과 선택").assertIsDisplayed()
    }

    @Test fun leavingParkClosesSearchDialog() {
        val parked = mutableStateOf(true)
        compose.setContent { DriverSelectionScreen(profiles, parked.value, true, true, {}, { _, _, _, _ -> }, { true }, {}) }
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("7")
        compose.runOnIdle { parked.value = false }
        compose.onNodeWithContentDescription("운전자 이름 검색").assertDoesNotExist()
        compose.onNodeWithText("주차 후 이용해 주세요").assertIsDisplayed()
    }

    @Test fun searchResultInManagementOpensEditor() {
        show()
        compose.onNodeWithText("프로필 관리", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("7")
        compose.onNodeWithContentDescription("운전자7 검색 결과 선택").performClick()
        compose.onNodeWithContentDescription("운전자 이름").assertTextContains("운전자7")
    }

    @Test fun managementHidesAddingUntilDoneAndKeepsEditingAvailable() {
        compose.setContent { DriverSelectionScreen(profiles.take(3), true, true, true, {}, { _, _, _, _ -> }, { true }, {}) }
        compose.onNodeWithContentDescription("운전자 추가").assertIsDisplayed()
        compose.onNodeWithText("프로필 관리").performClick()
        compose.onNodeWithText("완료").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자 추가").assertDoesNotExist()
        compose.onNodeWithText("운전자 추가").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자1 프로필 수정").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자1 프로필 삭제").assertIsDisplayed()
        screenshot("management-after")
        compose.onNodeWithText("완료").performClick()
        compose.onNodeWithText("프로필 관리").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자1 프로필 수정").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자 추가").assertIsDisplayed().performClick()
        compose.onNodeWithText("프로필 생성").assertIsDisplayed()
    }

    @Test fun emptyManagementCanExitWithBackAndRestoreAdding() {
        compose.setContent { DriverSelectionScreen(emptyList(), true, true, true, {}, { _, _, _, _ -> }, { true }, {}) }
        compose.onNodeWithText("프로필 관리").performClick()
        compose.onNodeWithContentDescription("운전자 추가").assertDoesNotExist()
        compose.onNodeWithText("완료").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("완료").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자 추가").assertIsDisplayed()
    }

    @Test fun queryAndManagementModeSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { DriverSelectionScreen(profiles, true, true, true, {}, { _, _, _, _ -> }, { true }, {}) }
        compose.onNodeWithText("더보기").performClick()
        compose.onNodeWithText("프로필 관리", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("7")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("운전자 이름 검색").assertTextContains("7")
        compose.onNodeWithContentDescription("검색 닫기").performClick()
        compose.onNodeWithText("완료").assertExists()
        compose.onNodeWithContentDescription("운전자 추가").assertDoesNotExist()
        compose.onNodeWithContentDescription("운전자7 프로필 수정").assertExists()
        compose.onNodeWithContentDescription("운전자6 프로필 수정").assertExists()
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
        compose.setContent { DriverSelectionScreen(profiles, parked.value, true, true, {}, { _, _, _, _ -> }, { true }, {}) }
        compose.onNodeWithContentDescription("운전자 추가").performClick()
        compose.onNodeWithContentDescription("운전자 이름").assertIsDisplayed()
        compose.runOnIdle { parked.value = false }
        compose.onNodeWithContentDescription("운전자 이름").assertDoesNotExist()
        compose.onNodeWithText("주차 후 이용해 주세요").assertIsDisplayed()
    }

    @Test fun createProfileValidatesNameAndSavesChosenColor() {
        val saved = mutableListOf<Pair<String, Int>>()
        compose.setContent { DriverSelectionScreen(emptyList(), true, true, true, {}, { _, name, color, _ -> saved.add(name to color) }, { true }, {}) }
        compose.onNodeWithContentDescription("운전자 추가").performClick()
        compose.onNodeWithText("저장").assertIsNotEnabled()
        compose.onNodeWithContentDescription("운전자 이름").performTextInput("  홍길동  ")
        compose.onNodeWithContentDescription("분홍").performClick()
        compose.onNodeWithContentDescription("기본 구름 캐릭터").performScrollTo().assertIsNotSelected()
        compose.onNodeWithText("홍길동", substring = false).assertExists()
        screenshot("editor-native")
        compose.onNodeWithText("저장").performClick()
        compose.waitForIdle()
        assertEquals(listOf("홍길동" to 2), saved)
    }

    @Test fun presetRestoresBlueAndEditingPreservesIdentity() {
        val saved = mutableListOf<Triple<Long?, String, Int>>()
        compose.setContent { DriverSelectionScreen(profiles, true, true, true, {}, { id, name, color, _ -> saved.add(Triple(id, name, color)) }, { true }, {}) }
        compose.onNodeWithText("프로필 관리", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("운전자2 프로필 수정").performClick()
        compose.onNodeWithContentDescription("운전자 이름").performTextReplacement("수정 이름")
        compose.onNodeWithContentDescription("기본 구름 캐릭터").performScrollTo().performClick()
        compose.onNodeWithContentDescription("하늘").performScrollTo().assertIsSelected()
        compose.onNodeWithText("저장").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple(2L, "수정 이름", 0)), saved)
    }

    @Test fun expressionsAndColorsCanBeChangedIndependentlyAndSaved() {
        val saved = mutableListOf<Triple<String, Int, Int>>()
        compose.setContent { DriverSelectionScreen(emptyList(), true, true, true, {}, { _, name, color, expression -> saved.add(Triple(name, color, expression)) }, { true }, {}) }
        compose.onNodeWithContentDescription("운전자 추가").performClick()
        compose.onNodeWithText("프로필 생성").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자 이름").performTextInput("미소 운전자")
        compose.onNodeWithContentDescription("미소 구름 캐릭터").performScrollTo().performClick()
        compose.onNodeWithContentDescription("미소 구름 캐릭터").assertIsSelected()
        compose.onNodeWithContentDescription("미소 표정").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("분홍").performScrollTo().assertIsSelected()
        screenshot("editor-expression-preset")
        compose.onNodeWithContentDescription("초록").performClick()
        compose.onNodeWithContentDescription("미소 표정").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("미소 구름 캐릭터").performScrollTo().assertIsNotSelected()
        compose.onNodeWithContentDescription("윙크 표정").performScrollTo().performClick()
        compose.onNodeWithContentDescription("초록").performScrollTo().assertIsSelected()
        compose.onNodeWithText("저장").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("미소 운전자", 4, 2)), saved)
    }

    @Test fun editingRestoresExpressionAndEveryPresetSetsItsOriginalCombination() {
        val profile = profiles.first().copy(color = 5, expression = 3)
        compose.setContent { DriverProfileEditor(profile, false, null, {}, { _, _, _ -> }) }
        compose.onNodeWithText("프로필 수정").assertIsDisplayed()
        compose.onNodeWithContentDescription("빨강").assertIsSelected()
        compose.onNodeWithContentDescription("버럭 표정").performScrollTo().assertIsSelected()
        ProfileExpressions.forEach { face ->
            compose.onNodeWithContentDescription("${face.name} 구름 캐릭터").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithContentDescription("${face.name} 표정").performScrollTo().assertIsSelected()
            compose.onNodeWithContentDescription(ProfileColorNames[face.presetColor]).performScrollTo().assertIsSelected()
        }
        compose.onNodeWithText("저장").assertIsDisplayed()
    }

    @Test fun deleteNeedsConfirmationAndCancelDoesNothing() {
        val deleted = mutableListOf<Long>()
        compose.setContent { DriverSelectionScreen(profiles, true, true, true, {}, { _, _, _, _ -> }, { deleted.add(it); true }, {}) }
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
        compose.setContent { DriverSelectionScreen(profiles, true, true, true, {}, { _, _, _, _ -> error("storage unavailable") }, { true }, {}) }
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

    @Test
    @Config(qualifiers = "w540dp-h420dp-land-night-mdpi")
    fun compactSearchDialogKeepsInputAndResultsAccessible() {
        show()
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").assertIsDisplayed().performTextInput("7")
        compose.onNodeWithContentDescription("운전자7 검색 결과 선택").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("운전자 검색", substring = false).assertDoesNotExist()
        compose.onNodeWithText("×", substring = false).assertDoesNotExist()
        val result = compose.onNodeWithContentDescription("운전자7 검색 결과 선택").fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithContentDescription("검색 닫기").assertIsDisplayed().assertTextContains("닫기").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(close.top > result.bottom)
        compose.onNodeWithContentDescription("검색 닫기").performClick()
        compose.onNodeWithContentDescription("운전자 검색 열기").assertTextContains("운전자 이름 검색...")
    }

    @Test
    @Config(qualifiers = "w500dp-h900dp-port-mdpi")
    fun allAppearancePickersScrollHorizontallyWithoutWrapping() {
        compose.setContent { DriverProfileEditor(null, false, null, {}, { _, _, _ -> }) }
        val horizontal = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)
        compose.onAllNodes(horizontal).assertCountEquals(3)
        listOf("실버", "느긋 표정", "느긋 구름 캐릭터").forEach { last ->
            compose.onNode(horizontal and hasAnyDescendant(hasContentDescription(last))).performScrollTo()
            compose.onNodeWithContentDescription(last).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        }
        compose.onAllNodes(horizontal).fetchSemanticsNodes().forEach { node ->
            val range = node.config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange]
            org.junit.Assert.assertTrue(range.maxValue() > 0f)
            org.junit.Assert.assertTrue(range.value() > 0f)
        }
        screenshot("editor-horizontal-pickers")
    }

    @Test
    @Config(qualifiers = "w540dp-h420dp-land-night-mdpi")
    fun keyboardInsetsKeepMatchingDriverAndCloseReachable() {
        val starts = mutableListOf<Long>()
        show(onStart = { starts.add(it) })
        compose.onNodeWithContentDescription("운전자 검색 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("운전자 이름 검색").performTextInput("7")
        compose.runOnIdle {
            val decor = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(
                decor,
                androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, 180))
                    .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), true)
                    .build(),
            )
        }
        compose.waitForIdle()
        val result = compose.onNodeWithContentDescription("운전자7 검색 결과 선택")
        result.performScrollTo().assertIsDisplayed()
        // 실제 IME insets가 전달되어 키보드 위 영역 안에 결과가 배치되었는지 검증한다.
        org.junit.Assert.assertTrue(result.fetchSemanticsNode().boundsInRoot.bottom <= 240f)
        compose.onNodeWithContentDescription("검색 닫기").performScrollTo().assertIsDisplayed()
        result.performScrollTo().performClick()
        assertEquals(listOf(7L), starts)
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            val view = dialog?.window?.decorView ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/driver-selection/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
