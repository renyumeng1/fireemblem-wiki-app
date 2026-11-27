package com.fireemblem.companion.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.SeedWorkbenchRecord
import com.fireemblem.companion.importer.AtomicBuddyArchiveStore
import com.fireemblem.companion.importer.FefwGrowthSeedSourceArchive
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.RouteMergeCharacter
import com.fireemblem.companion.personal.RouteMergeSnapshot
import com.fireemblem.companion.ui.theme.FireEmblemTheme
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RouteMergeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun recordsSameCharacterAcrossTwoRoutesAndDisplaysMergeComparison() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WikiDatabaseRepository.open(context).use { repo ->
            val firstChar = repo.page(
                com.fireemblem.companion.content.WikiPageRequest(categoryId = "characters", limit = 1),
            ).entries.first()

            rule.setContent {
                FireEmblemTheme {
                    RouteMergeScreen(
                        repository = repo,
                        onBack = {},
                        initialCharacterId = firstChar.id,
                    )
                }
            }

            rule.waitUntil(10_000) {
                rule.onAllNodesWithText(firstChar.title).fetchSemanticsNodes().isNotEmpty()
            }

            // Fill Kai line
            rule.onNodeWithTag("route-merge-detail").performScrollToNode(hasTestTag("route-merge-editor"))
            rule.onNodeWithTag("route-merge-level").performTextReplacement("18")
            rule.onNodeWithTag("route-merge-stat:HP").performTextReplacement("34")
            rule.onNodeWithTag("route-merge-stat:STRENGTH").performTextReplacement("20")
            rule.onNodeWithTag("route-merge-detail").performScrollToNode(hasTestTag("route-merge-save"))
            rule.onNodeWithTag("route-merge-save").performClick()

            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("已保存", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Switch to Leda line and record higher HP
            rule.onNodeWithTag("route-merge-detail").performScrollToNode(hasTestTag("route-merge-line:leda"))
            rule.onNodeWithTag("route-merge-line:leda").performClick()
            rule.onNodeWithTag("route-merge-detail").performScrollToNode(hasTestTag("route-merge-editor"))
            rule.onNodeWithTag("route-merge-level").performTextReplacement("21")
            rule.onNodeWithTag("route-merge-stat:HP").performTextReplacement("38")
            rule.onNodeWithTag("route-merge-stat:STRENGTH").performTextReplacement("17")
            rule.onNodeWithTag("route-merge-detail").performScrollToNode(hasTestTag("route-merge-save"))
            rule.onNodeWithTag("route-merge-save").performClick()

            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("蕾达篇（Lv.21", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            rule.onNodeWithTag("route-merge-detail").performScrollToNode(hasTestTag("route-merge-comparison"))
            rule.onNodeWithTag("route-merge-comparison").assertIsDisplayed()
            rule.onNodeWithText("融合最高").assertIsDisplayed()

            // Verify responsive full-width layout: the comparison table fills the card width cleanly with no right-side void
            val cardBounds = rule.onNodeWithTag("route-merge-comparison").fetchSemanticsNode().boundsInRoot
            val tableBounds = rule.onNodeWithTag("route-merge-comparison-table").fetchSemanticsNode().boundsInRoot
            val mergeHeaderBounds = rule.onNodeWithText("融合最高").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(
                "Table width (${tableBounds.width}) should fill comparison card (${cardBounds.width})",
                tableBounds.width >= cardBounds.width * 0.80f,
            )
            org.junit.Assert.assertTrue(
                "Rightmost column (${mergeHeaderBounds.right}) should reach near card right edge (${cardBounds.right})",
                mergeHeaderBounds.right >= cardBounds.right - 96f,
            )
        }
    }

    @Test
    fun personalManagerGroupsNamedRouteMergeAndSeedRecordsSafely() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "personal-group-test-").toFile()
        val testContext = object : android.content.ContextWrapper(target) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = File(tempRoot, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(tempRoot, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("${tempRoot.name}-$name", mode)
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <Input, Output> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<Input, Output>,
                    input: Input,
                    options: ActivityOptionsCompat?,
                ) = Unit
            }
        }
        try {
            val personalStore = PersonalStore(testContext)
            val archiveStore = AtomicBuddyArchiveStore(tempRoot.toPath())
            val rm1 = RouteMergeCharacter(
                profileId = "global",
                characterId = "characters:test-kai",
                characterName = "凯伊",
                snapshots = mapOf(
                    BuddyCampaignLine.KAI to RouteMergeSnapshot(BuddyCampaignLine.KAI, 15, "剑客", listOf(30, 18, 10, 17, 15, 12, 10, 9, 14)),
                ),
            )
            val rm2 = RouteMergeCharacter(
                profileId = "global",
                characterId = "characters:test-dit",
                characterName = "迪托利希",
                snapshots = mapOf(
                    BuddyCampaignLine.DIT to RouteMergeSnapshot(BuddyCampaignLine.DIT, 16, "侍道", listOf(31, 19, 11, 18, 16, 12, 11, 9, 13)),
                ),
            )
            personalStore.edit(RouteMergeCharacter.entityId("global", rm1.characterId), RouteMergeCharacter.KIND, rm1.payload(), false, emptySet())
            personalStore.edit(RouteMergeCharacter.entityId("global", rm2.characterId), RouteMergeCharacter.KIND, rm2.payload(), false, emptySet())
            val sw = SeedWorkbenchRecord(
                profileId = "global",
                characterId = "c044",
                line = BuddyCampaignLine.KAI,
                part3 = false,
                baseSeed = 123456L,
                inferredLevel = 5,
                startLevel = 6,
                extraAdvances = 0,
                profession = "士兵",
                ride = null,
                unlockedProfessions = setOf("士兵"),
                weights = List(9) { 1 },
                planSteps = emptyList(),
                previewOverrides = emptyMap(),
                completedSteps = 0,
            )
            personalStore.edit(UUID.randomUUID().toString(), SeedWorkbenchRecord.KIND, sw.encode(), false, emptySet())
            val src = FefwGrowthSeedSourceArchive.fromBytes("test-seed-source".toByteArray())
            val srcRev = src.toRevision()
            personalStore.edit(srcRev.entityId, FefwGrowthSeedSourceArchive.KIND, srcRev.payload, false, emptySet())

            rule.setContent {
                CompositionLocalProvider(
                    LocalContext provides testContext,
                    LocalActivityResultRegistryOwner provides registryOwner,
                ) {
                    FireEmblemTheme {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        ) {
                            PersonalManager(
                                legacy = archiveStore,
                                onArchivesChanged = {},
                                onOpenWiki = {},
                            )
                        }
                    }
                }
            }

            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("四线融合记录本 · 共 2 名角色", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            // Should show grouped named folder card & zero unnamed generic "个人记录" flat cards
            rule.onNodeWithText("四线融合记录本 · 共 2 名角色", substring = true).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("成长种子工作台方案 · 共 1 项", substring = true).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("已内置同步 1 份成长种子基础源数据", substring = true).performScrollTo().assertIsDisplayed()
            assertTrue(rule.onAllNodesWithText("个人记录").fetchSemanticsNodes().isEmpty())

            // Expand merge folder, open delete dialog for 凯伊, verify named confirmation appears
            rule.onNodeWithText("四线融合记录本 · 共 2 名角色", substring = true).performScrollTo()
            rule.onAllNodesWithText("展开名单 ▼")[0].performScrollTo().performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("凯伊 · 四线融合记录", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onAllNodesWithText("删除此条")[0].performScrollTo().performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("确认删除此记录").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("取消").performClick()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun overviewGlobalTogglePerCharacterOverrideAndDualBaselineDetailWorkEndToEnd() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "route-merge-overview-test-").toFile()
        val testContext = object : android.content.ContextWrapper(target) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = File(tempRoot, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(tempRoot, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("${tempRoot.name}-$name", mode)
        }
        try {
            val personalStore = PersonalStore(testContext)
            // Character 1: Kai + Dit + Leda recorded, custom saved selection {Kai, Leda}
            val charA = RouteMergeCharacter(
                profileId = "global",
                characterId = "characters:hero-a",
                characterName = "凯伊测试",
            ).withSnapshot(
                RouteMergeSnapshot(BuddyCampaignLine.KAI, 20, "勇士", listOf(34, 22, 8, 17, 21, 14, 15, 11, 16)),
            ).withSnapshot(
                RouteMergeSnapshot(BuddyCampaignLine.DIT, 22, "侍道", listOf(38, 19, 10, 23, 18, 16, 13, 12, 15)),
            ).withSnapshot(
                RouteMergeSnapshot(BuddyCampaignLine.LEDA, 19, "主教", listOf(32, 16, 20, 15, 17, 13, 21, 14, 18)),
            ).withSelectedLines(setOf(BuddyCampaignLine.KAI, BuddyCampaignLine.LEDA))
            // Character 2: Kai + Dit recorded, no custom override (follows global toggle)
            val charB = RouteMergeCharacter(
                profileId = "global",
                characterId = "characters:hero-b",
                characterName = "媞雅拉测试",
            ).withSnapshot(
                RouteMergeSnapshot(BuddyCampaignLine.KAI, 18, "魔道士", listOf(28, 10, 20, 16, 15, 12, 10, 16, 14)),
            ).withSnapshot(
                RouteMergeSnapshot(BuddyCampaignLine.DIT, 20, "贤者", listOf(30, 10, 25, 18, 17, 12, 11, 20, 15)),
            )
            personalStore.edit(RouteMergeCharacter.entityId("global", charA.characterId), RouteMergeCharacter.KIND, charA.payload(), false, emptySet())
            personalStore.edit(RouteMergeCharacter.entityId("global", charB.characterId), RouteMergeCharacter.KIND, charB.payload(), false, emptySet())

            val registryOwner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <Input, Output> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<Input, Output>,
                        input: Input,
                        options: ActivityOptionsCompat?,
                    ) = Unit
                }
            }
            rule.setContent {
                CompositionLocalProvider(
                    LocalContext provides testContext,
                    LocalActivityResultRegistryOwner provides registryOwner,
                ) {
                    FireEmblemTheme {
                        RouteMergeScreen(repository = null, onBack = {})
                    }
                }
            }

            // Lands directly on Overview with both characters visible
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("多线融合总览").fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithText("凯伊测试").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("route-merge-overview").assertIsDisplayed()
            rule.onNodeWithTag("route-merge-override-precedence").assertIsDisplayed()

            // Switch cleanly between table and cards density
            rule.onNodeWithTag("route-merge-view:cards").performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("route-merge-overview-card:characters:hero-a").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("route-merge-view:table").performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("route-merge-overview-table").fetchSemanticsNodes().isNotEmpty()
            }

            // Toggle off DIT globally -> charB drops from 158 to 141 (-17), while charA keeps saved {Kai,Leda} (181, -12)
            rule.onNodeWithTag("route-merge-global-line:dit").performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithText("-17", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Switch precedence to force global preview -> charA now also evaluated under {Kai,Seo,Leda} (still 181);
            // toggle off LEDA globally too so charA drops further under forced global!
            rule.onNodeWithTag("route-merge-override-precedence").performClick()
            rule.onNodeWithTag("route-merge-global-line:leda").performClick()
            rule.waitUntil(5_000) {
                // Under forced global {KAI, SEO}, charA only has KAI (total 158 vs full 193 -> -35)
                rule.onAllNodesWithText("-35", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Restore all 4 lines and click charA's visible left character cell to enter dual-baseline detail
            rule.onNodeWithTag("route-merge-overview").performScrollToNode(hasTestTag("route-merge-global-all"))
            rule.onNodeWithTag("route-merge-global-all").performClick()
            rule.onNodeWithTag("route-merge-overview").performScrollToNode(hasTestTag("route-merge-overview-table"))
            rule.onNodeWithTag("route-merge-overview-char:characters:hero-a").performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("route-merge-comparison").fetchSemanticsNodes().isNotEmpty()
            }
            // charA has saved subset {Kai, Leda} -> dual columns "自选融合" and "全线参考" appear!
            rule.onNodeWithTag("route-merge-detail").performScrollToNode(hasTestTag("route-merge-comparison"))
            rule.onNodeWithText("自选融合").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("全线参考").performScrollTo().assertIsDisplayed()

            // Toggle DIT back on in detail header -> single "融合最高" column returns without losing data
            rule.onNodeWithTag("route-merge-col-toggle:dit").performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithText("融合最高").fetchSemanticsNodes().isNotEmpty()
            }
            // Click "恢复全线" (now right above the table!) to clear custom override in PersonalStore
            rule.onNodeWithTag("route-merge-reset-selection").performClick()
            rule.waitUntil(5_000) {
                val snap = personalStore.snapshot()
                val reloaded = RouteMergeCharacter.readProfile(snap, "global").byCharacterId(charA.characterId)?.character
                reloaded != null && reloaded.selectedLines == null && reloaded.recordedLines.size == 3
            }

            // Return to overview via detail back button
            rule.onNodeWithTag("route-merge-detail").performScrollToNode(hasTestTag("route-merge-detail-back"))
            rule.onNodeWithTag("route-merge-detail-back").performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("route-merge-overview").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
