package com.framemayo.autolog.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.framemayo.autolog.data.local.*
import com.framemayo.autolog.ui.theme.AutoLogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1100dp-h1400dp-land-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecentTripsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val profiles = listOf(DriverProfile(DriverEntity(id = 1, name = "김운전", department = "개발"), "김운전", 0))
    private val state = MainUiState(profiles = profiles, drivers = profiles.map { it.driver }, linked = true)

    /** 최신 순. i번째 운행은 (i+1) km를 달렸다. */
    private fun trips(count: Int) = (0 until count).map { i ->
        val start = 1_800_000_000_000L - i * 3_600_000L
        TripEntity(
            id = i + 1L, driverId = if (i == 1) null else 1, startedAt = start, endedAt = start + 1_500_000,
            startOdometerKm = 100f, endOdometerKm = 101f + i, lastOdometerKm = 101f + i, lastSavedAt = start + 1_500_000,
            status = TripStatus.COMPLETED, purpose = if (i == 0) TripPurpose.UNCONFIRMED else TripPurpose.PERSONAL,
        )
    }

    private fun show(list: List<TripEntity>, width: Int = 1036, onOpen: (TripEntity) -> Unit = {}) {
        compose.setContent {
            AutoLogTheme {
                var page by remember { mutableIntStateOf(0) }
                LazyColumn(Modifier.width(width.dp).fillMaxSize().padding(16.dp)) {
                    recentTrips(list, state, page, { page = it }, onOpen)
                }
            }
        }
    }

    @Test fun onlyThreeSummaryCardsSideBySideAndRestPaginated() {
        show(trips(14))
        val first = compose.onNodeWithText("1.0 km").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithText("3.0 km").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(third.left > first.right)
        assertEquals(first.top, third.top)
        // 확인할 항목도 배지·강조색 없이 글자로만 보여 준다.
        compose.onNodeWithText("목적 미확인").assertIsDisplayed()
        compose.onNodeWithText("운전자 없음").assertIsDisplayed()
        for (badge in listOf("완료", "확인 필요", "전송 대기", "전송 완료", "전송 보류")) compose.onNodeWithText(badge).assertDoesNotExist()
        compose.onNodeWithText("4.0 km", substring = true).assertExists()
        compose.onNodeWithText("8.0 km", substring = true).assertExists()
        compose.onNodeWithText("9.0 km", substring = true).assertDoesNotExist()
        compose.onNodeWithText("1 / 3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("이전 페이지").assertIsNotEnabled()
        screenshot("recent-trips")
        compose.onNodeWithText("1.0 km").performScrollTo()

        compose.onNodeWithContentDescription("다음 페이지").performScrollTo().performClick()
        compose.onNodeWithText("2 / 3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("4.0 km", substring = true).assertDoesNotExist()
        compose.onNodeWithText("9.0 km", substring = true).assertExists()
        compose.onNodeWithContentDescription("다음 페이지").performScrollTo().performClick()
        compose.onNodeWithText("3 / 3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("14.0 km", substring = true).assertExists()
        compose.onNodeWithContentDescription("다음 페이지").assertIsNotEnabled()
        // 요약 카드는 페이지와 관계없이 유지한다.
        compose.onNodeWithText("1.0 km").performScrollTo().assertIsDisplayed()
    }

    @Test fun summaryCardAndHistoryRowOpenDetail() {
        val opened = mutableListOf<Long>()
        show(trips(5)) { opened += it.id }
        compose.onNodeWithText("2.0 km").performClick()
        compose.onNodeWithText("5.0 km", substring = true).performScrollTo().performClick()
        assertEquals(listOf(2L, 5L), opened)
    }

    @Test fun fewTripsShowNoPaginationAndNarrowWidthStacksCards() {
        show(trips(2), width = 600)
        compose.onNodeWithContentDescription("다음 페이지").assertDoesNotExist()
        val first = compose.onNodeWithText("1.0 km").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("2.0 km").fetchSemanticsNode().boundsInRoot
        assertTrue(second.top > first.bottom)
    }

    @Test fun emptyHistoryExplainsNoRecords() {
        show(emptyList())
        compose.onNodeWithText("아직 운행 기록이 없어요.").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/trip-review/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
