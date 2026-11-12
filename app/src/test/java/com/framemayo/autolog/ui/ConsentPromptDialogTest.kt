package com.framemayo.autolog.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1100dp-h900dp-land-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsentPromptDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun initialPromptShowsSeparateParagraphsAndBalancedActions() {
        var agreed = 0
        var declined = 0
        compose.setContent { ConsentPromptDialog("최현수", false, { agreed++ }, { declined++ }, {}) }
        compose.onNodeWithText("위치정보 수집 동의").assertIsDisplayed()
        compose.onNodeWithText("최현수님의 업무 운행에 한해 도착 위치를 저장합니다.").assertIsDisplayed()
        compose.onNodeWithText("개인 운행은 저장하지 않으며,\n동의하지 않아도 운행 기록은 정상 작성됩니다.").assertIsDisplayed()
        compose.runOnIdle {
            val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/consent-dialog/initial.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val agree = compose.onNodeWithText("동의").fetchSemanticsNode().boundsInRoot
        val decline = compose.onNodeWithText("동의 안 함").fetchSemanticsNode().boundsInRoot
        assertEquals(agree.width, decline.width, 1f)
        assertEquals(agree.top, decline.top, 1f)
        assertTrue(agree.height >= 64f)
        compose.onNodeWithText("동의 안 함").performClick()
        assertEquals(0, agreed)
        assertEquals(1, declined)
        compose.onNodeWithText("동의").performClick()
        assertEquals(1, agreed)
    }

    @Test fun declinedPromptKeepsBothStartChoices() {
        var agreed = 0
        var withoutConsent = 0
        compose.setContent { ConsentPromptDialog("최현수", true, { agreed++ }, { withoutConsent++ }, {}) }
        compose.onNodeWithText("위치정보 수집에 동의하지 않았어요").assertIsDisplayed()
        compose.onNodeWithText("최현수님의 업무 운행 도착 위치가 저장되지 않습니다.").assertIsDisplayed()
        compose.onNodeWithText("운행 거리와 시간은 정상 기록됩니다.").assertIsDisplayed()
        compose.onNodeWithText("동의 없이 시작").performClick()
        assertEquals(1, withoutConsent)
        assertEquals(0, agreed)
        compose.onNodeWithText("동의하고 시작").performClick()
        assertEquals(1, agreed)
    }

    @Test fun onlyBackdropDismissesThePrompt() {
        var dismissed = 0
        compose.setContent { ConsentPromptDialog("최현수", false, {}, {}, { dismissed++ }) }
        compose.onNodeWithText("위치정보 수집 동의").performTouchInput { click() }
        assertEquals(0, dismissed)
        compose.onNodeWithContentDescription("위치정보 동의 모달 배경").performTouchInput { click(Offset(4f, 4f)) }
        assertEquals(1, dismissed)
    }

    @Test
    @Config(qualifiers = "w480dp-h360dp-land-night-mdpi")
    fun compactPromptScrollsLongNoticeAndKeepsBothActionsVisible() {
        val name = "긴 이름을 가진 운전자 ".repeat(10)
        compose.setContent { ConsentPromptDialog(name, true, {}, {}, {}) }
        val agree = compose.onNodeWithText("동의하고 시작").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val decline = compose.onNodeWithText("동의 없이 시작").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(decline.bottom < agree.top)
        assertTrue(agree.bottom <= 360f)
        compose.onNodeWithText("운행 거리와 시간은 정상 기록됩니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("동의 없이 시작").assertIsDisplayed()
        compose.onNodeWithText("동의하고 시작").assertIsDisplayed()
    }
}
