package com.framemayo.autolog.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1100dp-h900dp-land-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverProfileConsentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun wideEditorFitsContentWithConsentBelowPresets() {
        compose.setContent { DriverProfileEditor(null, false, null, {}, { _, _, _, _ -> }) }
        compose.onNodeWithContentDescription("운전자 이름").performTextInput("샘플 운전자")
        val panel = compose.onNodeWithTag("profile-editor").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(panel.height <= 720f)
        val consent = compose.onNodeWithContentDescription("위치정보 수집 동의").assertIsDisplayed()
        val consentBounds = consent.fetchSemanticsNode().boundsInRoot
        val preset = compose.onNodeWithContentDescription("기본 구름 캐릭터").performScrollTo().assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val consentTitle = compose.onNodeWithText("위치정보 수집 동의 (선택)").fetchSemanticsNode().boundsInRoot
        assertTrue(consentTitle.top >= preset.bottom)
        assertTrue(consentTitle.top - preset.bottom <= 56f)
        compose.onNodeWithContentDescription("느긋 구름 캐릭터").performScrollTo()
        assertEquals(consentBounds, consent.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("저장").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("기본 구름 캐릭터").performScrollTo()
        screenshot("wide")
    }

    @Test fun creationAllowsEitherConsentChoiceAndScrollingKeepsConsentFixed() {
        val saved = mutableListOf<Boolean?>()
        compose.setContent { DriverProfileEditor(null, false, null, {}, { _, _, _, consent -> saved.add(consent) }) }
        compose.onNodeWithContentDescription("운전자 이름").performTextInput("새 운전자")
        compose.onNodeWithText("저장").assertIsEnabled()
        val check = compose.onNodeWithContentDescription("위치정보 수집 동의")
        check.assertIsDisplayed().assertIsOff()
        val before = check.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("느긋 구름 캐릭터").performScrollTo()
        assertEquals(before, check.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        check.performClick().assertIsOn()
        compose.onNodeWithText("저장").assertIsDisplayed().assertIsEnabled()
        check.performClick()
        compose.onNodeWithText("저장").assertIsEnabled()
        compose.onNodeWithText("저장").performClick()
        assertEquals(listOf(false), saved)
        check.performClick()
        compose.onNodeWithText("저장").performClick()
        assertEquals(listOf(false, true), saved)
    }

    @Test fun existingConsentCanBeUncheckedAndSaved() {
        val p = DriverProfile(DriverEntity(id = 1, name = "운전자", department = "", locationConsent = true), "운전자", 0)
        val saved = mutableListOf<Boolean?>()
        compose.setContent { DriverProfileEditor(p, false, null, {}, { _, _, _, consent -> saved.add(consent) }) }
        compose.onNodeWithContentDescription("위치정보 수집 동의").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("저장").assertIsEnabled().performClick()
        assertEquals(listOf(false), saved)
    }

    @Test fun unansweredDriverKeepsUnknownUnlessCheckboxIsChanged() {
        val p = DriverProfile(DriverEntity(id = 1, name = "운전자", department = "", locationConsent = null), "운전자", 0)
        val saved = mutableListOf<Boolean?>()
        compose.setContent { DriverProfileEditor(p, false, null, {}, { _, _, _, consent -> saved.add(consent) }) }
        compose.onNodeWithContentDescription("위치정보 수집 동의").assertIsOff()
        compose.onNodeWithText("저장").performClick()
        assertEquals(listOf<Boolean?>(null), saved)
        compose.onNodeWithContentDescription("위치정보 수집 동의").performClick().performClick()
        compose.onNodeWithText("저장").performClick()
        assertEquals(listOf(null, false), saved)
    }

    @Test
    @Config(qualifiers = "w540dp-h420dp-land-night-mdpi")
    fun shortWindowKeepsConsentAndSaveVisible() {
        compose.setContent { DriverProfileEditor(null, false, null, {}, { _, _, _, _ -> }) }
        compose.onNodeWithContentDescription("위치정보 수집 동의").assertIsDisplayed()
        compose.onNodeWithText("저장").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        fun capture(): Bitmap {
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }
        // 디코딩 완료 전의 빈 미리보기를 비교 이미지로 남기지 않는다.
        compose.waitUntil(timeoutMillis = 10_000) {
            var ready = false
            compose.runOnIdle {
                val bitmap = capture()
                val pixel = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2)
                ready = Color.blue(pixel) > Color.red(pixel) + 30 && Color.green(pixel) > Color.red(pixel) + 20
                bitmap.recycle()
            }
            ready
        }
        compose.runOnIdle {
            val bitmap = capture()
            val file = File("build/reports/profile-editor-spacing/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
