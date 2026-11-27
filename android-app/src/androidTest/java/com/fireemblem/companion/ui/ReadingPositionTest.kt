package com.fireemblem.companion.ui

import android.content.ContextWrapper
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReadingPositionTest {
    @get:Rule val compose = createComposeRule()
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "reading-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(preferenceName, mode)
    }

    @After fun cleanup() { target.deleteSharedPreferences(preferenceName) }

    @Test fun listAndGridPositionsSurviveNavigationAndLoadingWithoutSharingOffsets() {
        var page by mutableStateOf("list")
        var ready by mutableStateOf(true)
        lateinit var listState: LazyListState
        lateinit var gridState: LazyGridState
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                ReadingPositionProvider {
                    if (page == "list") {
                        listState = rememberReadingListState("source-list", ready)
                        LazyColumn(state = listState, modifier = Modifier.height(360.dp).testTag("list")) {
                            items(if (ready) 100 else 1) { Text("资料 $it", Modifier.height(60.dp)) }
                        }
                    } else {
                        gridState = rememberReadingGridState("source-grid", ready)
                        LazyVerticalGrid(columns = GridCells.Fixed(2), state = gridState,
                            modifier = Modifier.height(360.dp).testTag("grid")) {
                            items(if (ready) 100 else 1) { Text("角色 $it", Modifier.height(60.dp)) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("list").performScrollToIndex(30)
        compose.runOnIdle { assertEquals(30, listState.firstVisibleItemIndex); page = "grid" }
        compose.onNodeWithTag("grid").performScrollToIndex(44)
        compose.runOnIdle { assertEquals(44, gridState.firstVisibleItemIndex); ready = false; page = "list" }
        compose.waitForIdle()
        compose.runOnIdle { ready = true }
        compose.waitUntil(15000) { listState.firstVisibleItemIndex == 30 }
        compose.runOnIdle { ready = false; page = "grid" }
        compose.waitForIdle()
        compose.runOnIdle { ready = true }
        compose.waitUntil(15000) { gridState.firstVisibleItemIndex == 44 }
    }

    @Test fun disabledPreferenceStartsReturnedPagesAtTheTop() {
        context.getSharedPreferences("personal-view", 0).edit().putBoolean(RESTORE_READING_POSITION, false).commit()
        var visible by mutableStateOf(true)
        lateinit var listState: LazyListState
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                ReadingPositionProvider {
                    if (visible) {
                        listState = rememberReadingListState("disabled-list")
                        LazyColumn(state = listState, modifier = Modifier.height(360.dp).testTag("list")) {
                            items(100) { Text("资料 $it", Modifier.height(60.dp)) }
                        }
                    } else Text("详情")
                }
            }
        }
        compose.onNodeWithTag("list").performScrollToIndex(35)
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitUntil(15000) { listState.firstVisibleItemIndex == 0 }
        assertEquals(false, context.getSharedPreferences("personal-view", 0).getBoolean(RESTORE_READING_POSITION, true))
    }

    @Test fun regularScrollRestoresAfterShortLoadingContentExpands() {
        var visible by mutableStateOf(true)
        var ready by mutableStateOf(true)
        lateinit var scroll: ScrollState
        var before = 0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                ReadingPositionProvider {
                    if (visible) {
                        scroll = rememberReadingScrollState("personal")
                        Column(Modifier.height(360.dp).verticalScroll(scroll).testTag("scroll")) {
                            repeat(if (ready) 50 else 1) { Text("记录 $it", Modifier.height(60.dp)) }
                        }
                    } else Text("资料详情")
                }
            }
        }
        compose.onNodeWithTag("scroll").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { before = scroll.value; assertTrue(before > 0); visible = false }
        compose.waitForIdle()
        compose.runOnIdle { ready = false; visible = true }
        compose.waitForIdle()
        compose.runOnIdle { ready = true }
        compose.waitUntil(15000) { scroll.value == before }
    }
}
