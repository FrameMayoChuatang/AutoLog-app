package com.framemayo.autolog.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framemayo.autolog.data.local.DriverEntity
import com.framemayo.autolog.data.local.DriverProfile
import com.framemayo.autolog.data.local.TripEntity
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
class DrivingScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val trip = TripEntity(
        id = 1, driverId = 1, startedAt = 0, startOdometerKm = 100f, lastOdometerKm = 112.4f, lastSavedAt = 25 * 60_000L,
    )
    private val profile = DriverProfile(DriverEntity(id = 1, name = "김운전", department = ""), "김운전", 0)

    private fun show(parked: Boolean, canEnd: Boolean = parked, onEnd: () -> Unit = {}, onOpen: () -> Unit = {}, t: TripEntity = trip) {
        compose.setContent { DrivingScreen(t, if (t.driverId == null) "운전자 없음" else "김운전", profile.takeIf { t.driverId != null }, parked, canEnd, onEnd, onOpen) }
    }

    @Test fun showsDriverDistanceAndTime() {
        show(parked = false)
        compose.onNodeWithText("김운전").assertIsDisplayed()
        compose.onNodeWithText("12.4 km").assertIsDisplayed()
        compose.onNodeWithText("25분").assertIsDisplayed()
    }

    @Test fun endIsBlockedWhileDrivingAndDetailIsHidden() {
        show(parked = false)
        compose.onNodeWithText("운행 종료").assertIsNotEnabled()
        compose.onNodeWithText("주차(P) 후 종료할 수 있어요.").assertIsDisplayed()
        compose.onNodeWithText("차량 상태와 최근 운행 보기").assertDoesNotExist()
    }

    @Test fun parkedCanEndOrOpenDetail() {
        var ended = 0
        var opened = 0
        show(parked = true, onEnd = { ended++ }, onOpen = { opened++ })
        compose.onNodeWithText("운행 종료").assertIsEnabled().performClick()
        compose.onNodeWithText("차량 상태와 최근 운행 보기").performClick()
        assertEquals(1, ended)
        assertEquals(1, opened)
    }

    @Test fun tripWithoutDriverAsksToAssignLater() {
        show(parked = true, t = trip.copy(driverId = null))
        compose.onNodeWithText("운행이 끝나면 운전자를 지정해 주세요.").assertIsDisplayed()
    }
}
