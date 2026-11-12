package com.framemayo.autolog.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1100dp-h900dp-land-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileSearchWorkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class CountedProfiles(private val source: List<DriverProfile>) : AbstractList<DriverProfile>() {
        var reads = 0
        override val size get() = source.size
        override fun get(index: Int): DriverProfile { reads++; return source[index] }
    }

    @Test fun unchangedQueryAvoidsRescanButQueryAndProfileChangesRefreshResults() {
        val counted = CountedProfiles((1L..10_000L).map {
            DriverProfile(DriverEntity(id = it, name = "운전자$it", department = ""), "운전자$it", 0)
        })
        val profiles = mutableStateOf<List<DriverProfile>>(counted)
        val query = mutableStateOf("없는이름")
        val managing = mutableStateOf(false)
        compose.setContent {
            DriverSearchDialog(query.value, { query.value = it }, profiles.value,
                Rect(0f, 0f, 400f, 60f), managing.value, {}, {})
        }
        compose.waitForIdle()
        compose.onNodeWithText("검색 결과가 없습니다.").assertIsDisplayed()
        var before = 0
        compose.runOnIdle { before = counted.reads; managing.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("동일한 입력으로 목록을 재순회하지 않아야 합니다", before, counted.reads) }
        compose.runOnIdle { query.value = "운전자10000" }
        compose.onNodeWithContentDescription("운전자10000 검색 결과 선택").assertIsDisplayed()
        compose.runOnIdle { assertTrue(counted.reads >= before + 10_000) }
        compose.runOnIdle {
            profiles.value = listOf(DriverProfile(DriverEntity(id = 1, name = "다른 운전자", department = ""), "다른 운전자", 0))
        }
        compose.onNodeWithText("검색 결과가 없습니다.").assertIsDisplayed()
        compose.onNodeWithContentDescription("운전자10000 검색 결과 선택").assertDoesNotExist()
    }
}
