package com.framemayo.autolog.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1100dp-h900dp-land-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverProfileConsentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun creationRequiresConsentAndScrollingKeepsConsentFixed() {
        val saved = mutableListOf<Boolean>()
        compose.setContent { DriverProfileEditor(null, false, null, {}, { _, _, _, consent -> saved.add(consent) }) }
        compose.onNodeWithContentDescription("운전자 이름").performTextInput("새 운전자")
        compose.onNodeWithText("저장").assertIsNotEnabled()
        val check = compose.onNodeWithContentDescription("위치정보 수집 동의")
        check.assertIsDisplayed().assertIsOff()
        val before = check.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("느긋 구름 캐릭터").performScrollTo()
        assertEquals(before, check.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        check.performClick().assertIsOn()
        compose.onNodeWithText("저장").assertIsDisplayed().assertIsEnabled()
        check.performClick()
        compose.onNodeWithText("저장").assertIsNotEnabled()
        check.performClick()
        compose.onNodeWithText("저장").performClick()
        assertEquals(listOf(true), saved)
    }

    @Test fun existingConsentCanBeUncheckedAndSaved() {
        val p = DriverProfile(DriverEntity(id = 1, name = "운전자", department = "", locationConsent = true), "운전자", 0)
        val saved = mutableListOf<Boolean>()
        compose.setContent { DriverProfileEditor(p, false, null, {}, { _, _, _, consent -> saved.add(consent) }) }
        compose.onNodeWithContentDescription("위치정보 수집 동의").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("저장").assertIsEnabled().performClick()
        assertEquals(listOf(false), saved)
    }

    @Test
    @Config(qualifiers = "w540dp-h420dp-land-night-mdpi")
    fun shortWindowKeepsConsentAndSaveVisible() {
        compose.setContent { DriverProfileEditor(null, false, null, {}, { _, _, _, _ -> }) }
        compose.onNodeWithContentDescription("위치정보 수집 동의").assertIsDisplayed()
        compose.onNodeWithText("저장").assertIsDisplayed()
    }
}
