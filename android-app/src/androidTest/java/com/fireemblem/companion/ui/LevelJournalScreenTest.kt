package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.app.ActivityOptionsCompat
import com.fireemblem.companion.engine.*
import com.fireemblem.companion.importer.AtomicBuddyArchiveStore
import com.fireemblem.companion.personal.PersonalStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LevelJournalScreenTest {
    @get:Rule val compose = createComposeRule()
    private val verticalScrollToNodeAction = hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private val horizontalScrollToNodeAction = hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var store: PersonalStore
    private var selectedGrowthFile: Uri? = null
    private val entityId = UUID.randomUUID().toString()
    private val showSearchScreen = mutableStateOf(false)
    private val searchHistory = mutableStateOf<BuddySeedHistory?>(null)
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <Input, Output> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<Input, Output>,
                input: Input,
                options: ActivityOptionsCompat?,
            ) {
                selectedGrowthFile?.let { uri ->
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                }
            }
        }
    }

    @Before fun setup() {
        showSearchScreen.value = false
        searchHistory.value = null
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "journal-ui-test-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        store = PersonalStore(context)
        val rules = BuddyRules.bundled()
        val rows = rules.forecast(42, BuddyGrowthInput("c044", "平民", 1, false, BuddyRoute.KAI), count = 4).rows
        val baseJournal = LevelJournal("验收记录册", target.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global",
            BuddyCampaignLine.KAI, "c044", rows.map { row ->
                LevelObservation(level = row.fromLevel, profession = "平民", part3 = false,
                    gains = GrowthStat.entries.map { row.gains.getValue(it) })
            })
        val unlockedProfessions = setOf("平民")
        val checkpoint = JournalSeedCheckpoint(
            seed = rows.last().nextSeed,
            eventIndex = rows.size,
            level = rows.last().fromLevel + 1,
            profession = "平民",
            part3 = false,
            stats = List(GrowthStat.entries.size) { 0 },
            caps = List<Int?>(GrowthStat.entries.size) { null },
            evidence = JournalSeedEvidence.MANUAL,
            historyDigest = baseJournal.historyDigest(),
            originSeed = 42,
            unlockedProfessions = unlockedProfessions,
        )
        val journal = baseJournal.copy(seedCheckpoint = checkpoint, unlockedProfessions = unlockedProfessions)
        store.edit(entityId, LevelJournal.KIND, journal.encode(), expectedParents = emptySet())
        store.edit(UUID.randomUUID().toString(), LevelJournal.KIND, journal.copy(name = "第二册").encode(), expectedParents = emptySet())
        listOf("c001", "c078").forEachIndexed { index, characterId ->
            store.edit(UUID.randomUUID().toString(), LevelJournal.KIND,
                LevelJournal("同名主角 ${index + 1}", journal.profile, BuddyCampaignLine.KAI, characterId).encode(),
                expectedParents = emptySet())
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                MaterialTheme {
                    if (showSearchScreen.value) {
                        searchHistory.value?.let { history ->
                            SeedSearchScreen(
                                archiveStore = AtomicBuddyArchiveStore(File(root, "archives").toPath()),
                                archives = emptyList(),
                                onBack = {},
                                onChooseBackup = {},
                                recordedHistory = history,
                                recordLabel = "同名主角记录",
                            )
                        }
                    } else {
                        LevelJournalScreen(AtomicBuddyArchiveStore(File(root, "archives").toPath()), onBack = {})
                    }
                }
            }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithText("验收记录册").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun routesAreSeparateAndNewObservationIsDurable() {
        listOf("迪托利希篇", "赛奥朵拉篇", "蕾达篇").forEach { route ->
            compose.onNodeWithText(route).performScrollTo().performClick()
            compose.onNodeWithText("验收记录册").assertDoesNotExist()
        }
        compose.onNodeWithText("凯伊篇").performScrollTo().performClick()
        compose.onNodeWithText("验收记录册").performClick()
        compose.onNodeWithText("记录本次升级").performClick()
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("保存实测记录"))
        compose.onNodeWithText("保存实测记录").performClick()
        compose.waitUntil(10000) { store.snapshot().heads().getValue(entityId).single().let { LevelJournal.decode(it.payload).observations.size == 5 } }
        val journal = LevelJournal.decode(store.snapshot().heads().getValue(entityId).single().payload)
        assertEquals(5, journal.observations.last().level)
        assertEquals(List(9) { 0 }, journal.observations.last().gains)
    }

    @Test fun portraitsAndGainsUseMultipleColumns() {
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("伊欧头像").fetchSemanticsNodes().size == 2 }
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val visibleCards = listOf("验收记录册", "第二册", "同名主角 1", "同名主角 2")
            .flatMap { title -> compose.onAllNodesWithText(title).fetchSemanticsNodes().map { it.boundsInRoot } }
        org.junit.Assert.assertTrue("Phone-width journal cards should share a row inside $rootBounds: $visibleCards",
            visibleCards.any { first -> visibleCards.any { second ->
                kotlin.math.abs(first.top - second.top) < 1f && kotlin.math.abs(first.left - second.left) > 1f
            } })
        compose.onNodeWithText("验收记录册").performClick()
        compose.onNodeWithText("记录本次升级").performClick()
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("HP +0"))
        val hp = compose.onNodeWithText("HP +0").fetchSemanticsNode().boundsInRoot
        val strength = compose.onNodeWithText("力量 +0").fetchSemanticsNode().boundsInRoot
        val magic = compose.onNodeWithText("魔力 +0").fetchSemanticsNode().boundsInRoot
        assertEquals(hp.top, strength.top, 1f)
        assertEquals(hp.top, magic.top, 1f)
        org.junit.Assert.assertTrue(hp.left < strength.left && strength.left < magic.left)
    }

    @Test fun duplicateProtagonistsUseDistinctLabelsInJournalList() {
        val rules = BuddyRules.bundled()
        val labels = listOf("c001", "c078").map { id ->
            val character = rules.characters.first { it.id == id }
            characterLabel(character.id, character.name)
        }

        labels.forEach { label ->
            compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test fun sameRouteJournalsCanQuicklySwitchCharacters() {
        val rules = BuddyRules.bundled()
        val male = rules.characters.first { it.id == "c001" }
        val female = rules.characters.first { it.id == "c078" }
        val maleLabel = characterLabel(male.id, male.name)
        val femaleLabel = characterLabel(female.id, female.name)

        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText(maleLabel))
        compose.onNodeWithText(maleLabel).performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("${maleLabel}头像").fetchSemanticsNodes().size == 2
        }

        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("同路线记录"))
        if (compose.onAllNodes(horizontalScrollToNodeAction).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(horizontalScrollToNodeAction).performScrollToNode(hasText(femaleLabel))
        }
        compose.onNodeWithText(femaleLabel).performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithContentDescription("${femaleLabel}头像").fetchSemanticsNodes().size == 2 &&
                compose.onAllNodesWithContentDescription("${maleLabel}头像").fetchSemanticsNodes().size <= 1 &&
                compose.onAllNodesWithText("同名主角 2").fetchSemanticsNodes().size == 2
        }
    }

    @Test fun duplicateProtagonistsUseDistinctLabelsInSeedSearch() {
        val rules = BuddyRules.bundled()
        val male = rules.characters.first { it.id == "c001" }
        val female = rules.characters.first { it.id == "c078" }
        searchHistory.value = LevelJournal("同名主角", "global", BuddyCampaignLine.KAI, male.id).history(rules)
        showSearchScreen.value = true

        val maleLabel = characterLabel(male.id, male.name)
        val femaleLabel = characterLabel(female.id, female.name)
        compose.waitUntil(10000) { compose.onAllNodesWithText(maleLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(maleLabel).assertIsDisplayed()
        searchHistory.value = LevelJournal("同名主角", "global", BuddyCampaignLine.KAI, female.id).history(rules)
        compose.waitUntil(10000) { compose.onAllNodesWithText(femaleLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(femaleLabel).assertIsDisplayed()
    }

    @Test fun importedRouteChoicesDoNotOverlapAndRemainSelectable() {
        val growthFile = File(root, "growth.json")
        growthFile.writeText("""{"format":"fefw-growth-seed","version":1,"records":[
            {"pid":"PID_セオドラ","job":"JID_兵士","part":"0","level":10,
             "observations":[[1,0,1,0,0,0,0,1,0]]}
        ]}""")
        selectedGrowthFile = Uri.fromFile(growthFile)
        compose.onNodeWithText("导入成长记录").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("选择赛奥朵拉的成长记录（第 1 条）").assertIsSelected()
        compose.onNode(verticalScrollToNodeAction and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText("选择所属路线"))
        val choices = BuddyCampaignLine.entries.map { line ->
            compose.onNode(hasText(line.label) and hasAnyAncestor(isDialog()))
        }
        choices.last().performScrollTo()
        val bounds = choices.map { it.assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.forEachIndexed { index, first ->
            bounds.drop(index + 1).forEach { second ->
                org.junit.Assert.assertTrue("Import route choices must not overlap: $first and $second",
                    first.right <= second.left || second.right <= first.left ||
                        first.bottom <= second.top || second.bottom <= first.top)
            }
        }
        choices.forEach { it.performScrollTo().assertIsDisplayed().performClick().assertIsSelected() }
    }

    @Test fun importsUserConfirmedChaptersRouteAndRecordsThenUndoesActualSteps() {
        val growthFile = File(root, "growth-chapters.json")
        val source = """{"format":"fefw-growth-chapters","version":1,"revision":0,"entries":[
            {"id":"source-route","chapter":"赛奥朵拉篇","round":1,"name":"力量优先路线",
             "snapshot":{"activeRoute":0,"position":0,"recordPosition":0,
              "inputs":{"pid":"PID_セオドラ","job":"JID_兵士","part":"0","level":1},
              "routes":[{"name":"力量优先","forecast":{"seed":42,"level":1,
                "currentStats":[0,0,0,0,0,0,0,0,0],"caps":[null,null,null,null,null,null,null,null,null]},
                "plan":[{"job":"JID_兵士","mountId":"","exams":0,"kind":"level"},
                  {"job":"JID_猟兵","mountId":"","exams":0,"kind":"exam"},
                  {"job":"JID_猟兵","mountId":"","exams":0,"kind":"level"}],
                "position":0,"recordPosition":0}]},
             "execution":{"seed":42,"level":1,"currentStats":[0,0,0,0,0,0,0,0,0],"job":"JID_兵士","cursor":0}}
        ]}"""
        growthFile.writeText(source, Charsets.UTF_8)
        selectedGrowthFile = Uri.fromFile(growthFile)

        compose.onNodeWithText("导入成长记录").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("种子按你已确认的文件起点保存", substring = true).assertIsDisplayed()
        compose.onNode(verticalScrollToNodeAction and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText("选择战役线"))
        compose.onNode(hasText(BuddyCampaignLine.KAI.label) and hasAnyAncestor(isDialog()))
            .performScrollTo().performClick()
        compose.onNodeWithText("新记录册名称").performScrollTo().performTextReplacement("导入路线验收")
        compose.onNodeWithText("创建路线记录册").assertIsEnabled().performClick()

        fun importedJournal(): LevelJournal? = store.snapshot().heads().values.mapNotNull { group ->
            group.singleOrNull()?.takeIf { it.kind == LevelJournal.KIND }?.let { revision ->
                runCatching { LevelJournal.decode(revision.payload) }.getOrNull()
            }
        }.singleOrNull { it.name == "导入路线验收" }

        compose.waitUntil(15000) { importedJournal() != null }
        val initial = importedJournal()!!
        assertEquals(emptyList<LevelObservation>(), initial.observations)
        assertEquals(42L, initial.seedCheckpoint?.seed)
        assertEquals(JournalSeedEvidence.USER_CONFIRMED_IMPORT, initial.seedCheckpoint?.evidence)
        assertEquals(0, initial.importedGrowthPlan?.nextStepIndex)
        assertEquals(3, initial.importedGrowthPlan?.actions?.size)
        val originalArchive = requireNotNull(store.growthSeedSourceArchive(requireNotNull(initial.importSourceSha256)))
        assertArrayEquals(growthFile.readBytes(), originalArchive.restoreBytes())

        compose.onNodeWithText("录入这步的实际成长").performScrollTo().performClick()
        compose.onNodeWithText("保存实际成长并继续路线").performScrollTo()
        compose.onNodeWithText("保存实际成长并继续路线").assertIsEnabled().performClick()
        compose.waitUntil(15000) { importedJournal()?.importedGrowthPlan?.nextStepIndex == 1 }
        assertEquals(List(9) { 0 }, importedJournal()!!.observations.single().gains)
        assertNull(importedJournal()!!.seedCheckpoint)

        compose.onNodeWithText("确认这次考试结果").performScrollTo().performClick()
        compose.onNodeWithText("未通过").performClick()
        compose.onNodeWithText("保存考试结果").performClick()
        compose.waitUntil(15000) { importedJournal()?.importedGrowthPlan?.nextStepIndex == 2 }
        val afterExam = importedJournal()!!
        assertFalse(afterExam.observations.last().exam!!.passed)
        assertNull(afterExam.observations.last().exam!!.probability)
        assertNull(afterExam.observations.last().exam!!.resourceCost)
        assertEquals(2, afterExam.currentLevel)

        compose.onNodeWithText("撤销最近一次路线确认").performScrollTo().performClick()
        compose.onNodeWithText("撤销这一步").performClick()
        compose.waitUntil(15000) { importedJournal()?.importedGrowthPlan?.nextStepIndex == 1 }
        assertEquals(1, importedJournal()!!.observations.size)
        val importedRevisions = store.snapshot().revisions.filter { revision ->
            revision.kind == LevelJournal.KIND &&
                runCatching { LevelJournal.decode(revision.payload).name == "导入路线验收" }.getOrDefault(false)
        }
        assertEquals(4, importedRevisions.size)
        assertEquals(1, store.snapshot().heads().getValue(importedRevisions.first().entityId).size)
    }

    @Test fun importedExamsCanBeCorrectedWithoutInventingMissingDetails() {
        val growthFile = File(root, "exam-growth.json")
        growthFile.writeText("""{"format":"fefw-growth-seed","version":1,"records":[
            {"pid":"PID_セオドラ","job":"JID_兵士","part":"0","level":10,"currentLevel":11,
             "observations":[[1,0,1,0,0,0,0,1,0]],
             "observationRecord":{"version":1,"choices":[],"counts":[1],
               "details":[[{"success":false}]],"trailing":[{"success":true}]}}
        ]}""", Charsets.UTF_8)
        selectedGrowthFile = Uri.fromFile(growthFile)
        compose.onNodeWithText("导入成长记录").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("实测记录：1 次升级，2 次考试").performScrollTo().assertIsDisplayed()
        compose.onNode(verticalScrollToNodeAction and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText("选择所属路线"))
        compose.onNode(hasText(BuddyCampaignLine.KAI.label) and hasAnyAncestor(isDialog()))
            .performScrollTo().performClick()
        compose.onNodeWithText("新记录册名称").performScrollTo().performTextReplacement("导入考试验收")
        compose.onNodeWithText("创建新记录册").assertIsEnabled().performClick()
        fun importedJournal(): LevelJournal? = store.snapshot().heads().values
            .mapNotNull { heads -> heads.singleOrNull()?.payload?.let { payload ->
                runCatching { LevelJournal.decode(payload) }.getOrNull()
            } }.singleOrNull { it.name == "导入考试验收" }
        compose.waitUntil(15000) { importedJournal() != null }
        assertEquals(listOf(false, true), importedJournal()!!.observations.mapNotNull { it.exam?.passed })
        val imported = importedJournal()!!
        assertEquals(0, imported.importSourceRecordIndex)
        val archivedSource = requireNotNull(store.growthSeedSourceArchive(requireNotNull(imported.importSourceSha256)))
        assertEquals(growthFile.readBytes().toList(), archivedSource.restoreBytes().toList())
        assertEquals(1, store.snapshot().revisions.count { it.kind == "growth-seed-source" })
        compose.onNodeWithText("导出原始成长文件").performScrollTo().assertIsDisplayed()
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("纠正考试"))
        compose.onAllNodesWithText("纠正考试")[0].performClick()
        compose.onNodeWithText("游戏显示的成功率（0–100）").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithContentDescription("考试结果").performScrollTo().performClick()
        compose.onNodeWithText("保存考试结果").assertIsEnabled().performClick()
        compose.waitUntil(15000) { importedJournal()?.observations?.first()?.exam?.passed == true }
        val saved = importedJournal()!!
        assertEquals(11, saved.currentLevel)
        saved.observations.mapNotNull { it.exam }.forEach { exam ->
            assertEquals(null, exam.probability)
            assertEquals(null, exam.resourceCost)
            assertEquals("", exam.targetProfession)
        }

        compose.waitUntil(15000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("升级记录"))
        val backLabel = if (compose.onAllNodesWithText("返回记录册").fetchSemanticsNodes().isNotEmpty()) {
            "返回记录册"
        } else {
            "返回工具"
        }
        compose.onNodeWithText(backLabel).performScrollTo().performClick()
        compose.onNodeWithText("导入成长记录").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(verticalScrollToNodeAction and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText("选择所属路线"))
        compose.onNode(hasText(BuddyCampaignLine.KAI.label) and hasAnyAncestor(isDialog()))
            .performScrollTo().performClick()
        compose.onNodeWithText("这条实测记录已在当前路线").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("打开已导入记录").assertIsEnabled().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("导入考试验收").fetchSemanticsNodes().size >= 2 }
        compose.onAllNodesWithText("导入考试验收")[1].performScrollTo().assertIsDisplayed()

        val matchingImportedJournals = store.snapshot().heads().values.mapNotNull { group ->
            group.singleOrNull()?.takeIf { it.kind == LevelJournal.KIND }?.let { revision ->
                runCatching { LevelJournal.decode(revision.payload) }.getOrNull()
            }
        }.filter {
            it.importSourceSha256 == imported.importSourceSha256 &&
                it.importSourceRecordIndex == 0 && it.line == BuddyCampaignLine.KAI
        }
        assertEquals(1, matchingImportedJournals.size)
    }

    @Test fun importedBatchCanBeCorrectedWithoutDroppingItsGrowthLevel() {
        val growthFile = File(root, "batch-growth.json")
        growthFile.writeText("""{"format":"fefw-growth-seed","version":1,"records":[
            {"pid":"PID_セオドラ","job":"JID_兵士","part":"0","level":10,"currentLevel":12,
             "observations":[[9,0,0,0,0,0,0,0,0],[0,0,0,0,0,0,0,0,0]],"batches":"2",
             "observationRecord":{"version":1,"choices":[],"counts":[0,0],"details":[[],[]],"trailing":[]}}
        ]}""", Charsets.UTF_8)
        selectedGrowthFile = Uri.fromFile(growthFile)
        compose.onNodeWithText("导入成长记录").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(verticalScrollToNodeAction and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasText("选择所属路线"))
        compose.onNode(hasText(BuddyCampaignLine.KAI.label) and hasAnyAncestor(isDialog()))
            .performScrollTo().performClick()
        compose.onNodeWithText("新记录册名称").performScrollTo().performTextReplacement("批次纠正验收")
        compose.onNodeWithText("创建新记录册").assertIsEnabled().performClick()
        fun importedJournal(): LevelJournal? = store.snapshot().heads().values
            .mapNotNull { heads -> heads.singleOrNull()?.payload?.let { payload ->
                runCatching { LevelJournal.decode(payload) }.getOrNull()
            } }.singleOrNull { it.name == "批次纠正验收" }
        compose.waitUntil(15000) { importedJournal() != null }
        val originalIds = importedJournal()!!.observations.map { it.id }
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("纠正记录"))
        compose.onAllNodesWithText("纠正记录")[0].performClick()
        compose.onNodeWithContentDescription("本次跳过（不升级，只推进种子）").performScrollTo().performClick()
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("保存实测记录"))
        compose.onNodeWithText("保存实测记录").assertIsNotEnabled()
        compose.onNodeWithContentDescription("本次跳过（不升级，只推进种子）").performScrollTo().performClick()
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("HP +9"))
        compose.onNodeWithText("HP +9").performClick()
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("保存实测记录"))
        compose.onNodeWithText("保存实测记录").assertIsEnabled().performClick()
        compose.waitUntil(15000) { importedJournal()?.observations?.first()?.gains?.first() == 0 }
        val saved = importedJournal()!!
        assertEquals(originalIds, saved.observations.map { it.id })
        assertEquals(listOf(11, 11), saved.observations.map { it.growthLevel })
        assertEquals(12, saved.currentLevel)
        assertEquals(emptyList<String>(), saved.history(BuddyRules.bundled()).errors)
    }

    @Test fun observationAndJournalControlsHaveAccessibleLabels() {
        compose.onNodeWithText("验收记录册").performClick()
        compose.onNodeWithText("记录本次升级").performClick()
        compose.onNodeWithContentDescription("第三部").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("本次跳过（不升级，只推进种子）").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("有属性接近或达到上限").performScrollTo().assertIsOff()
        compose.onNodeWithText("取消，返回记录册").performScrollTo().performClick()
        compose.onNodeWithText("记录考试结果").performScrollTo().performClick()
        compose.onNodeWithContentDescription("考试结果").assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "考试通过"))
            .performClick().assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "考试未通过"))
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("已解锁职业 (", substring = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("平民").performScrollTo().assertIsOn()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("批量管理记录").performScrollTo().performClick()
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasContentDescription("选择第 1 条记录"))
        compose.onNodeWithContentDescription("选择第 1 条记录").assertIsDisplayed().assertIsOff()
            .performClick().assertIsOn()
    }

    @Test fun bundledArtworkCoversEngineRosterAndProfessions() {
        val root = context.assets.open("wiki/buddy/index.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        assertEquals(BuddyRules.REVISION, root.getValue("revision").jsonPrimitive.content)
        val images = root.getValue("images").jsonObject
        val characters = images.getValue("characters").jsonObject
        val rules = BuddyRules.bundled()
        assertEquals(rules.characters.map { it.id }.toSet(), characters.keys)
        assertEquals(rules.professions.map { it.name }.toSet(), images.getValue("professions").jsonObject.keys)
        org.junit.Assert.assertNotEquals(characters.getValue("c001"), characters.getValue("c078"))
        org.junit.Assert.assertTrue(characters.getValue("c076").jsonPrimitive.content.endsWith("credna.webp"))
        images.values.flatMap { it.jsonObject.values }.map { it.jsonPrimitive.content }.distinct().forEach { path ->
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open("wiki/$path").use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
            org.junit.Assert.assertTrue(path, options.outWidth > 0 && options.outHeight > 0)
        }
    }

    @Test fun savedObservationsCanSearchAndCorrectionInvalidatesOldResult() {
        compose.onNodeWithText("验收记录册").performClick()
        compose.onNodeWithText("重新反推").performClick()
        compose.onNodeWithText("起始种子（含）").performScrollTo().performTextReplacement("42")
        compose.onNodeWithText("结束种子（不含）").performScrollTo().performTextReplacement("43")
        compose.onNodeWithText("用当前记录重新反推").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("当前范围内唯一匹配").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回记录册").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("记录本次升级").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("纠正记录"))
        compose.onAllNodesWithText("纠正记录")[0].performScrollTo().performClick()
        compose.onNodeWithText("HP +", substring = true).performScrollTo().performClick()
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("保存实测记录"))
        compose.onNodeWithText("保存实测记录").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("用这些记录反推种子").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("用这些记录反推种子").performScrollTo().performClick()
        compose.onNodeWithText("当前范围内唯一匹配").assertDoesNotExist()
        compose.onNodeWithText("起始种子（含）").performScrollTo().performTextReplacement("42")
        compose.onNodeWithText("结束种子（不含）").performScrollTo().performTextReplacement("43")
        compose.onNodeWithText("用当前记录重新反推").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("当前范围没有匹配").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("记录间存在不可能条件").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun selectedSeedPlanRemainsLinkedThroughActualUpgradeConfirmation() {
        compose.onNodeWithText("验收记录册").performClick()
        compose.onNodeWithText("重新反推").performClick()
        compose.onNodeWithText("起始种子（含）").performScrollTo().performTextReplacement("42")
        compose.onNodeWithText("结束种子（不含）").performScrollTo().performTextReplacement("43")
        compose.onNodeWithText("用当前记录重新反推").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("当前范围内唯一匹配").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("保存此候选种子"))
        compose.waitForIdle()
        compose.onNodeWithText("保存此候选种子").assertIsEnabled().performClick()
        val seedWasSaved = runCatching {
            compose.waitUntil(20000) { compose.onAllNodesWithText("当前种子 ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }.isSuccess
        if (!seedWasSaved) {
            compose.onRoot().printToLog("seed-save-state")
        }
        assertEquals(true, seedWasSaved)

        compose.onNodeWithText("乱数预览与培养规划").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("自动规划").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("自动规划").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("自定义目标等级").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("自定义目标等级").performTextReplacement("6")
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("生成最优转职方案"))
        compose.onNodeWithText("生成最优转职方案").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("关联到记录册").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("关联到记录册"))
        compose.onNodeWithText("关联到记录册").performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("已关联培养方案 · 还剩 1 步").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("记录这步实际结果").performScrollTo().performClick()
        compose.onNode(verticalScrollToNodeAction).performScrollToNode(hasText("确认实测并更新方案"))
        compose.onNodeWithText("确认实测并更新方案").performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("培养方案已完成").fetchSemanticsNodes().isNotEmpty()
        }

        val journal = LevelJournal.decode(store.snapshot().heads().getValue(entityId).single().payload)
        assertEquals(5, journal.observations.size)
        assertEquals(JournalSeedEvidence.PLAN_CONFIRMED, journal.seedCheckpoint?.evidence)
        assertEquals(6, journal.seedCheckpoint?.level)
        assertEquals(1, journal.growthPlan?.nextStepIndex)
        assertEquals(true, journal.hasCurrentGrowthPlan(BuddyRules.bundled()))
    }
}
