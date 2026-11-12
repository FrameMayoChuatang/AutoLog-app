package com.framemayo.autolog.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.framemayo.autolog.data.local.TripPurpose
import com.framemayo.autolog.data.sync.HoldReason
import com.framemayo.autolog.data.sync.TripSyncStatus
import com.framemayo.autolog.ui.theme.AutoLogTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1100dp-h900dp-land-night-mdpi")
class TripSyncStatusNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun liveStateChangesExplainWaitingFailureAndEveryHoldReason() {
        val status = mutableStateOf<TripSyncStatus>(TripSyncStatus.Waiting)
        compose.setContent { AutoLogTheme { TripSyncStatusNotice(status.value, TripPurpose.BUSINESS_GENERAL) } }
        compose.onNodeWithText("전송 대기").assertIsDisplayed()
        for ((reason, text) in listOf(
            HoldReason.NOT_LINKED to "차량을 연결하면",
            HoldReason.INTERRUPTED to "실제 종료값",
            HoldReason.NO_DRIVER to "운전자를 지정하면",
            HoldReason.DRIVER_UNREGISTERED to "서버에 등록되기를",
            HoldReason.DRIVER_PENDING to "관리자의 운전자 승인",
            HoldReason.DRIVER_INACTIVE to "비활성 운전자",
        )) {
            compose.runOnIdle { status.value = TripSyncStatus.Held(reason) }
            compose.onNodeWithText("전송 보류").assertIsDisplayed()
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        }
        compose.runOnIdle { status.value = TripSyncStatus.Failed }
        compose.onNodeWithText("전송 실패").assertIsDisplayed()
        compose.onNodeWithText("관리자에게", substring = true).assertIsDisplayed()
        compose.runOnIdle { status.value = TripSyncStatus.Synced }
        compose.onNodeWithText("전송 완료").assertIsDisplayed()
    }

    @Test fun uploadedMeasurementsStillAskForUnconfirmedPurpose() {
        compose.setContent { AutoLogTheme { TripSyncStatusNotice(TripSyncStatus.Synced, TripPurpose.UNCONFIRMED) } }
        compose.onNodeWithText("목적을 선택해", substring = true).assertIsDisplayed()
    }
}
