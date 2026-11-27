package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.SupportPairRequest
import com.fireemblem.companion.content.SupportPairSummary
import com.fireemblem.companion.content.SupportRelationFilter
import com.fireemblem.companion.content.WikiDatabaseRepository
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SupportGuideScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "support-guide-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("${root.name}-$name", mode)
        }
        repository = WikiDatabaseRepository.open(context)
    }

    @After fun tearDown() {
        repository.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("${root.name}-personal-view")
        root.deleteRecursively()
    }

    @Test fun opensDialogueDetailsChoosesAJobAndReturnsToTheFilteredList() {
        val pair = requireNotNull(repository.supportPairs(SupportPairRequest(
            filter = SupportRelationFilter.WITH_DIALOGUES,
            limit = 1,
        ))).entries.single()
        val job = requireNotNull(repository.supportGuide()).jobs.first { it.matchStatus.name == "MATCHED" }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { SupportGuideScreen(repository, onBack = {}, onOpenWiki = {}) }
            }
        }

        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("support-filter-with_dialogues").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasTestTag("support-filter-with_dialogues"))
        compose.onNodeWithTag("support-filter-with_dialogues").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("support-pair-${pair.pairId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("support-pair-${pair.pairId}").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("support-detail-person-${pair.pairId}-first").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasTestTag("support-dialogues-heading"))
        compose.onNodeWithText("支援对话 · ${pair.dialogueCount} 条条件").assertIsDisplayed()
        compose.onNodeWithTag("support-guide-grid").performScrollToNode(hasText("组合加成"))
        compose.onNodeWithText("组合加成").assertIsDisplayed()
        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasTestTag("support-level-5"))
        compose.onNodeWithTag("support-level-5").performClick()
        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasTestTag("support-job-picker"))
        compose.onNodeWithTag("support-job-picker").performClick()
        replaceQuery("support-job-search", job.displayName)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("support-job-option-${job.sourceId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("support-job-option-${job.sourceId}").performClick()
        compose.onNodeWithText(job.displayName).assertIsDisplayed()
        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasText("职业等级补正"))
        compose.onNodeWithText("职业等级补正").assertIsDisplayed()
        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasText("合计补正"))
        compose.onNodeWithText("合计补正").assertIsDisplayed()

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("support-pair-${pair.pairId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("support-pair-${pair.pairId}").assertIsDisplayed()
    }

    @Test fun filtersByAnExactCharacterAndKeepsBothPortraitsReachableOnNarrowScreens() {
        val guide = requireNotNull(repository.supportGuide())
        val person = guide.people.first()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).fillMaxHeight()) {
                        SupportGuideScreen(repository, onBack = {}, onOpenWiki = {})
                    }
                }
            }
        }

        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasTestTag("support-character-picker"))
        compose.onNodeWithTag("support-character-picker").assertIsDisplayed().performClick()
        replaceQuery("support-character-search", person.displayName)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("support-character-option-${person.sourceName}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("support-character-option-${person.sourceName}").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("63 组关系").fetchSemanticsNodes().isNotEmpty()
        }
        val pair = requireNotNull(repository.supportPairs(SupportPairRequest(characterName = person.sourceName, limit = 1)))
            .entries.single()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("support-pair-${pair.pairId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasTestTag("support-pair-${pair.pairId}"))
        compose.onNodeWithTag("support-pair-${pair.pairId}").assertIsDisplayed()
        compose.onNodeWithTag("support-pair-person-${pair.pairId}-first", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("support-pair-person-${pair.pairId}-second", useUnmergedTree = true)
            .assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("support-filter-all").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun opensAnUnrecordedPairAtItsInitialLevelAndRestoresTheFilteredPage() {
        val pair = pairWithInitialLevel()
        val characterPairs = requireNotNull(repository.supportPairs(
            SupportPairRequest(characterName = pair.first.sourceName, limit = 100),
        ))
        val pairIndex = characterPairs.entries.indexOfFirst { it.pairId == pair.pairId }
        assertTrue(pairIndex >= 0)
        val targetPage = pairIndex / SupportPairRequest.PAGE_SIZE

        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { SupportGuideScreen(repository, onBack = {}, onOpenWiki = {}) }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("support-character-picker").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasTestTag("support-character-picker"))
        compose.onNodeWithTag("support-character-picker").performClick()
        replaceQuery("support-character-search", pair.first.displayName)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("support-character-option-${pair.first.sourceName}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("support-character-option-${pair.first.sourceName}").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("${characterPairs.totalMatches} 组关系").fetchSemanticsNodes().isNotEmpty()
        }

        repeat(targetPage) { pageIndex ->
            compose.onNodeWithTag("support-guide-grid")
                .performScrollToNode(hasTestTag("support-page-next"))
            compose.onNodeWithTag("support-page-next").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("第 ${pageIndex + 2} 页 · 共 ${characterPairs.totalMatches} 组")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }

        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasTestTag("support-pair-${pair.pairId}"))
        compose.onNodeWithTag("support-pair-${pair.pairId}").performClick()
        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasTestTag("support-level-${pair.initialLevel.coerceIn(1, 5)}"))
        compose.onNodeWithTag("support-level-${pair.initialLevel.coerceIn(1, 5)}").assertIsSelected()
        compose.onNodeWithText("初始等级：${pair.initialLevel}").assertIsDisplayed()
        compose.onNodeWithTag("support-guide-grid")
            .performScrollToNode(hasText("来源未收录这组角色的支援对话"))
        compose.onNodeWithText("来源未收录这组角色的支援对话").assertIsDisplayed()

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("support-pair-${pair.pairId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("support-pair-${pair.pairId}").assertIsDisplayed()
    }

    private fun replaceQuery(tag: String, value: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(value)
    }

    private fun pairWithInitialLevel(): SupportPairSummary {
        var offset = 0
        while (true) {
            val page = requireNotNull(repository.supportPairs(SupportPairRequest(offset = offset, limit = 100)))
            page.entries.firstOrNull { it.initialLevel > 1 }?.let { return it }
            if (!page.hasNext) error("Expected a support pair with a non-default initial level")
            offset += page.limit
        }
    }
}
