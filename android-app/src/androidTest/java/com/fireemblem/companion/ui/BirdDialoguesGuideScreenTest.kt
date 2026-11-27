package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.BirdDialogueRequest
import com.fireemblem.companion.content.BirdDialogueReviewFilter
import com.fireemblem.companion.content.WikiDatabaseRepository
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BirdDialoguesGuideScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "bird-dialogues-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences("${root.name}-$name", mode)
        }
        repository = WikiDatabaseRepository.open(context)
    }

    @After fun tearDown() {
        repository.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("${root.name}-personal-view")
        root.deleteRecursively()
    }

    @Test fun opensACharacterDialogueAndReturnsToTheSameSearchResults() {
        val expected = requireNotNull(repository.birdDialogues(BirdDialogueRequest(query = "上了年纪睡眠")))
            .entries.single()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { BirdDialoguesGuideScreen(repository = repository, onBack = {}) }
            }
        }

        replaceQuery("上了年纪睡眠")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("bird-dialogue-card-${expected.entryId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("bird-dialogue-card-${expected.entryId}").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("来源未记录英文对话原文").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("温柔地歌唱").assertIsDisplayed()
        compose.onNodeWithText("已标记校订").assertIsDisplayed()
        compose.onNodeWithText("返回对话列表").performClick()
        compose.onNodeWithTag("bird-dialogue-card-${expected.entryId}").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("如有错误记得反馈").fetchSemanticsNodes().isEmpty())
    }

    @Test fun narrowWindowKeepsRoleAndReviewFiltersReachable() {
        val target = requireNotNull(repository.birdDialogues(BirdDialogueRequest(
            reviewFilter = BirdDialogueReviewFilter.UNMARKED,
            limit = 100,
        ))).entries.first()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(800.dp)) {
                        BirdDialoguesGuideScreen(repository = repository, onBack = {})
                    }
                }
            }
        }

        compose.onNodeWithTag("bird-dialogue-group-all").assertIsDisplayed()
        compose.onNodeWithTag("bird-dialogue-review-unmarked").performClick()
        when (target.characterGroup) {
            "主角" -> compose.onNodeWithTag("bird-dialogue-group-protagonist").performClick()
            "可招募角色" -> compose.onNodeWithTag("bird-dialogue-group-recruitable").performClick()
            null -> Unit
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("bird-dialogue-card-${target.entryId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("bird-dialogue-card-${target.entryId}").assertIsDisplayed()
    }

    @Test fun contentWidthIsCenteredAndCardsAdaptToAvailableSpace() {
        val entries = requireNotNull(repository.birdDialogues(BirdDialogueRequest(limit = 2))).entries
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { BirdDialoguesGuideScreen(repository = repository, onBack = {}) }
            }
        }

        entries.forEach { entry ->
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("bird-dialogue-card-${entry.entryId}").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("bird-dialogue-card-${entry.entryId}").performScrollTo()
        }

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("bird-dialogues-grid").fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val availableWidthDp = root.width / density
        val gridWidthDp = grid.width / density
        assertEquals("Grid should remain centered within the available width", root.center.x, grid.center.x, 1f)
        assertEquals("Grid should not exceed its readable width cap", minOf(availableWidthDp, 1040f), gridWidthDp, 2f)

        val first = compose.onNodeWithTag("bird-dialogue-card-${entries[0].entryId}")
            .fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("bird-dialogue-card-${entries[1].entryId}")
            .fetchSemanticsNode().boundsInRoot
        if (availableWidthDp >= 660f) {
            assertEquals("Cards should share a row when two readable columns fit", first.top, second.top, 1f)
        }
    }

    private fun replaceQuery(value: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("bird-dialogue-search", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("bird-dialogue-search", useUnmergedTree = true).performTextReplacement(value)
    }
}
