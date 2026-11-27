package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.campaign.CampaignGuideProjector
import com.fireemblem.companion.campaign.CampaignGuideTask
import com.fireemblem.companion.campaign.CampaignTaskKind
import com.fireemblem.companion.campaign.CampaignTaskFacet
import com.fireemblem.companion.campaign.CampaignTaskProgress
import com.fireemblem.companion.campaign.CampaignTaskStatus
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.personal.PersonalStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CampaignGuideScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var profilePreferences: SharedPreferences
    private lateinit var repository: WikiDatabaseRepository

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "campaign-guide-ui-").toFile()
        profilePreferences = target.getSharedPreferences("campaign-guide-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = root
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "personal-view") profilePreferences else super.getSharedPreferences(name, mode)
        }
        profilePreferences.edit().putString("profile", "profile-a").commit()
        repository = WikiDatabaseRepository.open(context)
    }

    @After fun tearDown() {
        compose.runOnIdle { repository.close() }
        profilePreferences.edit().clear().commit()
        root.deleteRecursively()
    }

    @Test fun routeTaskProgressIsSeparateAndFilterable() {
        val kaiTasks = loadTasks(BuddyCampaignLine.KAI)
        val ditTasks = loadTasks(BuddyCampaignLine.DIT)
        val sharedTask = kaiTasks.firstOrNull { task -> ditTasks.any { it.entryId == task.entryId } }
            ?: throw AssertionError("Expected at least one task shared by two routes")
        showScreen()
        waitForTask(sharedTask, BuddyCampaignLine.KAI)

        compose.onNodeWithTag(statusTag(sharedTask, "completed")).performScrollTo().performClick()
        compose.onNodeWithText("已保存到当前存档的凯伊篇任务清单。", substring = true).performScrollTo().assertIsDisplayed()
        val savedHeads = PersonalStore(context).snapshot().heads().getValue(
            CampaignTaskProgress.entityId("profile-a", BuddyCampaignLine.KAI.id, sharedTask.entryId),
        )
        assertEquals(CampaignTaskStatus.COMPLETED, CampaignTaskProgress.fromRevision(savedHeads.single())?.status)
        waitForSummary("凯伊篇", "已完成/已招募 1")
        compose.onNodeWithTag("campaign-filter-completed").performScrollTo().performClick()
        scrollToTask(sharedTask)
        compose.onNodeWithTag(taskTag(sharedTask)).assertIsDisplayed()
        compose.onNodeWithTag("campaign-filter-all").performScrollTo().performClick()

        compose.onNodeWithTag("campaign-route-dit").performScrollTo().performClick()
        waitForTask(sharedTask, BuddyCampaignLine.DIT)
        compose.onNodeWithTag(statusTag(sharedTask, "todo")).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(statusTag(sharedTask, "completed")).performScrollTo().performClick()
        compose.onNodeWithText("已保存到当前存档的迪托利希篇任务清单。", substring = true).performScrollTo().assertIsDisplayed()
        waitForSummary("迪托利希篇", "已完成/已招募 1")

        compose.onNodeWithTag("campaign-route-kai").performScrollTo().performClick()
        waitForTask(sharedTask, BuddyCampaignLine.KAI)
        compose.onNodeWithTag(statusTag(sharedTask, "completed")).performScrollTo().assertIsSelected()
    }

    @Test fun missedTasksDoNotAppearInTheCompletedFilter() {
        val task = loadTasks(BuddyCampaignLine.KAI).first()
        showScreen()
        waitForTask(task, BuddyCampaignLine.KAI)

        compose.onNodeWithTag(statusTag(task, "missed")).performScrollTo().performClick()
        compose.onNodeWithText("已保存到当前存档的凯伊篇任务清单。", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("campaign-filter-completed").performScrollTo().performClick()
        compose.onNodeWithTag(taskTag(task)).assertDoesNotExist()
        compose.onNodeWithTag("campaign-filter-missed").performScrollTo().performClick()
        waitForTask(task, BuddyCampaignLine.KAI)

        val heads = PersonalStore(context).snapshot().heads().getValue(
            CampaignTaskProgress.entityId("profile-a", BuddyCampaignLine.KAI.id, task.entryId),
        )
        assertEquals(CampaignTaskStatus.MISSED, CampaignTaskProgress.fromRevision(heads.single())?.status)
        compose.onNodeWithTag(taskTag(task)).assertIsDisplayed()
    }

    @Test fun eachCampaignLineHasOfflineTasksAndDistinctStatusChoices() {
        showScreen()
        BuddyCampaignLine.entries.forEach { line ->
            compose.onNodeWithTag("campaign-route-${line.id}").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("${line.label} ·", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(loadTasks(line).isNotEmpty())
        }
        compose.onNodeWithTag("campaign-filter-missed").assertIsDisplayed()
    }

    @Test fun routeRecruitmentShowsChapterAndConditionAndSavesJoinedState() {
        val recruit = loadTasks(BuddyCampaignLine.KAI).first { it.kind == CampaignTaskKind.RECRUITMENT }
        showScreen()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("凯伊篇 ·", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("campaign-kind-recruitment").performScrollTo().performClick()
        waitForTask(recruit, BuddyCampaignLine.KAI)
        compose.onNodeWithTag(statusTag(recruit, "todo")).assertIsSelected()
        recruit.availability?.let {
            assertTrue(compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty())
        }
        recruit.timing?.let {
            assertTrue(compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty())
        }

        compose.onNodeWithTag(statusTag(recruit, "completed")).performScrollTo().performClick()
        compose.onNodeWithText("已保存到当前存档的凯伊篇任务清单。", substring = true).performScrollTo().assertIsDisplayed()
        val heads = PersonalStore(context).snapshot().heads().getValue(
            CampaignTaskProgress.entityId("profile-a", BuddyCampaignLine.KAI.id, recruit.entryId),
        )
        assertEquals(CampaignTaskStatus.COMPLETED, CampaignTaskProgress.fromRevision(heads.single())?.status)
        compose.onNodeWithTag(statusTag(recruit, "completed")).assertIsSelected()
    }

    @Test fun timelineIncludesCommonChaptersAndSavesRouteScopedProgress() {
        val timeline = loadTasks(BuddyCampaignLine.KAI).filter { it.kind == CampaignTaskKind.TIMELINE }
        assertEquals(14, timeline.size)
        assertTrue(timeline.any { it.isCommon })
        val event = timeline.first { !it.isCommon }
        showScreen()
        compose.onNodeWithTag("campaign-kind-timeline").performScrollTo().performClick()
        waitForTask(event, BuddyCampaignLine.KAI)
        compose.onNodeWithTag(statusTag(event, "completed")).performScrollTo().performClick()
        compose.onNodeWithText("已保存到当前存档的凯伊篇任务清单。", substring = true).performScrollTo().assertIsDisplayed()
        val heads = PersonalStore(context).snapshot().heads().getValue(
            CampaignTaskProgress.entityId("profile-a", BuddyCampaignLine.KAI.id, event.entryId),
        )
        assertEquals(CampaignTaskStatus.COMPLETED, CampaignTaskProgress.fromRevision(heads.single())?.status)
    }

    @Test fun routeGuideFiltersParaloguesByExactDeadline() {
        val paralogues = loadTasks(BuddyCampaignLine.KAI).filter {
            it.kind == CampaignTaskKind.PARALOGUE && !it.deadline.isNullOrBlank()
        }
        val target = paralogues.first()
        val otherDeadlineTask = paralogues.firstOrNull { it.deadline != target.deadline }
            ?: throw AssertionError("Expected paralogues with different deadlines")
        val options = CampaignTaskFacet.DEADLINE.options(paralogues)
        val optionIndex = options.indexOf(target.deadline)
        assertTrue(optionIndex >= 0)

        showScreen()
        compose.onNodeWithTag("campaign-kind-paralogue").performScrollTo().performClick()
        waitForTask(target, BuddyCampaignLine.KAI)
        compose.onNodeWithTag("campaign-facet-deadline").performScrollTo().performClick()
        compose.onNodeWithTag("campaign-facet-value").performScrollTo().performClick()
        compose.onNodeWithTag("campaign-facet-option-$optionIndex").performClick()

        scrollToTask(target)
        compose.onNodeWithTag(taskTag(target)).assertIsDisplayed()
        compose.onNodeWithTag(taskTag(otherDeadlineTask)).assertDoesNotExist()
    }

    @Test fun routeTaskShowsItsExplicitTimingWithoutOtherCampaignLines() {
        val task = loadTasks(BuddyCampaignLine.KAI).first {
            it.kind == CampaignTaskKind.SIDE_QUEST && it.title == "请来道具店看看吧"
        }

        showScreen()
        compose.onNodeWithTag("campaign-search").performClick().performTextInput(task.title)
        waitForTask(task, BuddyCampaignLine.KAI)
        compose.onNodeWithText("第1部 凯伊篇 第2章 2月3日 鞑古席翁中层 市区").assertIsDisplayed()
        compose.onNodeWithText("第1部 迪托利希篇 第4章 3月27日 鞑古席翁中层 市区").assertDoesNotExist()
    }

    @Test fun challengeBookIsDietrichOnlyAndCanBeMarkedComplete() {
        val challenges = loadTasks(BuddyCampaignLine.DIT).filter { it.kind == CampaignTaskKind.CHALLENGE }
        assertEquals(9, challenges.size)
        BuddyCampaignLine.entries.filter { it != BuddyCampaignLine.DIT }.forEach { line ->
            assertTrue(loadTasks(line).none { it.kind == CampaignTaskKind.CHALLENGE })
        }
        val challenge = challenges.first()
        showScreen()
        compose.onNodeWithTag("campaign-route-dit").performScrollTo().performClick()
        compose.onNodeWithTag("campaign-kind-challenge").performScrollTo().performClick()
        waitForTask(challenge, BuddyCampaignLine.DIT)
        compose.onNodeWithTag(statusTag(challenge, "completed")).performScrollTo().performClick()
        compose.onNodeWithText("已保存到当前存档的迪托利希篇任务清单。", substring = true).performScrollTo().assertIsDisplayed()
        val heads = PersonalStore(context).snapshot().heads().getValue(
            CampaignTaskProgress.entityId("profile-a", BuddyCampaignLine.DIT.id, challenge.entryId),
        )
        assertEquals(CampaignTaskStatus.COMPLETED, CampaignTaskProgress.fromRevision(heads.single())?.status)
    }

    private fun loadTasks(line: BuddyCampaignLine): List<CampaignGuideTask> {
        val entries = buildList<WikiEntry> {
            listOf("side-quests", "paralogues", "characters", "campaign-timeline", "challenge-book")
                .forEach { categoryId ->
                var offset = 0
                do {
                    val page = repository.page(WikiPageRequest(categoryId = categoryId, offset = offset, limit = 100))
                    page.entries.forEach { summary -> repository.entry(summary.id)?.let { add(it) } }
                    offset += page.entries.size
                } while (page.hasNext)
            }
        }
        return CampaignGuideProjector.project(line, entries)
    }

    private fun showScreen() {
        compose.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context) {
                    CampaignGuideScreen(repository = repository, onBack = {}, onOpenWiki = {})
                }
            }
        }
    }

    private fun waitForTask(task: CampaignGuideTask, line: BuddyCampaignLine) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("${line.label} ·", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        scrollToTask(task)
        compose.onNodeWithTag(taskTag(task)).assertIsDisplayed()
    }

    private fun waitForSummary(lineLabel: String, fragment: String) {
        val matched = runCatching { compose.waitUntil(10_000) {
            val summaries = compose.onAllNodesWithText("$lineLabel ·", substring = true).fetchSemanticsNodes()
            if (summaries.isEmpty()) return@waitUntil false
            runCatching { compose.onNodeWithTag("campaign-summary").assertTextContains(fragment, substring = true) }.isSuccess
        } }.isSuccess
        if (!matched) {
            val actual = runCatching {
                compose.onNodeWithTag("campaign-summary").fetchSemanticsNode().config
                    .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
            }.getOrDefault("摘要尚未显示")
            throw AssertionError("Expected summary to contain '$fragment'; actual: $actual")
        }
    }

    private fun scrollToTask(task: CampaignGuideTask) {
        compose.onNode(hasScrollToNodeAction() and hasTestTag("campaign-grid"))
            .performScrollToNode(hasTestTag(taskTag(task)))
    }

    private fun taskTag(task: CampaignGuideTask) = "campaign-task-${task.entryId}"
    private fun statusTag(task: CampaignGuideTask, status: String) = "campaign-status-${task.entryId}-$status"
}
