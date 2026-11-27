package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.app.ActivityOptionsCompat
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiField
import com.fireemblem.companion.content.WikiLink
import com.fireemblem.companion.content.WikiCharacterSkill
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.personal.PersonalStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WikiCatalogScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository
    private lateinit var characterId: String

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "wiki-ui-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences("${root.name}-$name", mode)
        }
        repository = WikiDatabaseRepository.open(context)
        characterId = repository.page(WikiPageRequest(categoryId = "characters", query = "丹提"))
            .entries.first { it.title == "丹提" }.id
    }

    @After fun tearDown() {
        repository.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("${root.name}-personal-view")
        root.deleteRecursively()
    }

    @Test fun professionGrowthModifiersUseQuartileBandsAndDistinctSurfaceColors() {
        assertEquals(GrowthModifierBand.LOW, growthModifierBand("-5%"))
        assertEquals(GrowthModifierBand.MEDIUM, growthModifierBand("0%"))
        assertEquals(GrowthModifierBand.MEDIUM, growthModifierBand("5.0"))
        assertEquals(GrowthModifierBand.HIGH, growthModifierBand("10%"))
        assertEquals(null, growthModifierBand("未知"))

        val professionId = repository.page(WikiPageRequest(categoryId = "professions", query = "德鲁伊"))
            .entries.first { it.title == "德鲁伊" }.id
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    WikiCatalogPane(
                        repository = repository,
                        query = "",
                        selectedCategoryId = "professions",
                        selectedEntryId = professionId,
                        onQueryChange = {},
                        onOpenCategory = {},
                        onOpenEntry = {},
                        onBackToCategories = {},
                        onCloseEntry = {},
                        onCompare = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("成长修正").fetchSemanticsNodes().isNotEmpty() }

        val statColors = listOf("HP", "力量", "魅力").map { stat ->
            val image = compose.onNodeWithTag("wiki-stat-tone-成长修正-$stat")
                .performScrollTo().captureToImage()
            val pixels = image.toPixelMap()
            pixels[pixels.width / 2, 2]
        }

        assertEquals(
            "Negative, neutral, and strong modifiers should have distinct fills: $statColors",
            3,
            statColors.toSet().size,
        )
        compose.onNodeWithText("分档依据：63 个职业的 567 项成长修正，Q1 0% · 中位数 5% · Q3 10%；0%表示没有职业加减。")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun homeHeroScrimUsesTheDarkThemeForegroundPalette() {
        val scrim = homeHeroScrimColor(Color(0xFFE5EDF5))
        assertTrue("The scrim should preserve the light dark-theme primary: $scrim", scrim.red > 0.65f &&
            scrim.green > 0.68f && scrim.blue > 0.72f)
    }

    @Test fun homeCatalogDistinguishesLoadingFailureAndReadyWithRetry() {
        var state by mutableStateOf<AtlasLibraryState>(AtlasLibraryState.Loading)
        var retryRequested = false
        compose.setContent {
            MaterialTheme {
                HomeWikiCatalogContent(
                    state = state,
                    onRetry = { retryRequested = true },
                    onOpenCategory = {},
                )
            }
        }
        compose.onNodeWithText("正在打开离线资料…").fetchSemanticsNode()

        compose.runOnIdle { state = AtlasLibraryState.Failed("数据库校验失败") }
        compose.onNodeWithText("数据库校验失败").fetchSemanticsNode()
        compose.onNodeWithText("重新检查").performClick()
        compose.runOnIdle { assertTrue(retryRequested) }

        compose.runOnIdle { state = AtlasLibraryState.Ready(repository) }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(repository.catalog.categories.first().title).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun characterDetailsGroupPortraitGrowthAndLongEffects() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    WikiCatalogPane(
                        repository = repository,
                        query = "",
                        selectedCategoryId = "characters",
                        selectedEntryId = characterId,
                        onQueryChange = {},
                        onOpenCategory = {},
                        onOpenEntry = {},
                        onBackToCategories = {},
                        onCloseEntry = {},
                        onCompare = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("成长率").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("图鉴").fetchSemanticsNodes().isEmpty())
        val titleTop = compose.onNode(hasText("丹提") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .fetchSemanticsNode().boundsInRoot.top
        val compareBounds = compose.onNodeWithText("加入比较").fetchSemanticsNode().boundsInRoot
        val favoriteBounds = compose.onNodeWithText("收藏").fetchSemanticsNode().boundsInRoot
        val noteBounds = compose.onNodeWithText("笔记").fetchSemanticsNode().boundsInRoot
        assertTrue("Entity actions should follow the image and title", compareBounds.top > titleTop)
        assertTrue("Favorite may wrap, but must follow the image and title", favoriteBounds.top > titleTop)
        assertTrue("Note may wrap, but must follow the image and title", noteBounds.top > titleTop)
        compose.onNodeWithText("成长率").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("wiki-character-learning", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("丹提").fetchSemanticsNode()
        assertTrue(compose.onAllNodesWithText("偏低").fetchSemanticsNodes().size > 1)
        assertTrue(compose.onAllNodesWithText("中等").fetchSemanticsNodes().size > 1)
        assertTrue(compose.onAllNodesWithText("偏高").fetchSemanticsNodes().size > 1)
        compose.onNodeWithText("初始能力").fetchSemanticsNode()
        compose.onNodeWithText("特技效果").fetchSemanticsNode()
        compose.onNodeWithTag("wiki-stat-成长率-HP").performScrollTo()
        val hp = compose.onNodeWithTag("wiki-stat-成长率-HP").fetchSemanticsNode().boundsInRoot
        val strength = compose.onNodeWithTag("wiki-stat-成长率-力量").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(hp.top - strength.top) < 1f)
        assertTrue("HP bounds $hp should precede Strength bounds $strength", hp.left < strength.left)
        compose.onNodeWithText("资料来源：01-全可加入角色信息", substring = true).fetchSemanticsNode()
    }

    @Test fun growthRateBandsFollowObservedDistributionBoundaries() {
        assertEquals(GrowthRateBand.LOW, growthRateBand("30%"))
        assertEquals(GrowthRateBand.MEDIUM, growthRateBand("31%"))
        assertEquals(GrowthRateBand.MEDIUM, growthRateBand("44.9%"))
        assertEquals(GrowthRateBand.HIGH, growthRateBand("45%"))
        assertNull(growthRateBand("—"))
        assertNull(growthRateBand(""))
    }

    @Test fun characterSkillRelationshipOpensTheLinkedSkill() {
        var openedEntryId: String? = null
        val skillId = repository.page(WikiPageRequest(categoryId = "skills", query = "战场剧作家"))
            .entries.first { it.title == "战场剧作家" }.id
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    WikiCatalogPane(
                        repository = repository,
                        query = "",
                        selectedCategoryId = "characters",
                        selectedEntryId = characterId,
                        onQueryChange = {},
                        onOpenCategory = {},
                        onOpenEntry = { openedEntryId = it },
                        onBackToCategories = {},
                        onCloseEntry = {},
                        onCompare = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val learnedSkill = repository.characterLearning(characterId).skills.first { it.name == "战场剧作家" }
        waitForLearning()
        compose.onNodeWithTag("wiki-character-learning", useUnmergedTree = true).performScrollTo()
        compose.onNodeWithTag(
            "wiki-character-learning-${learnedSkill.groupPosition}-${learnedSkill.position}",
            useUnmergedTree = true,
        )
            .performScrollTo().performClick()
        assertEquals(skillId, openedEntryId)
    }

    @Test fun characterLearningShowsRankAndOpensTheMatchedSkill() {
        val character = repository.page(WikiPageRequest(categoryId = "characters", query = "凯伊"))
            .entries.first { it.title == "凯伊" }
        val learning = repository.characterLearning(character.id)
        val knownSkill = learning.skills.first { it.name == "黑炎涡流" }
        var openedEntryId: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    WikiCatalogPane(
                        repository = repository,
                        query = "",
                        selectedCategoryId = "characters",
                        selectedEntryId = character.id,
                        onQueryChange = {},
                        onOpenCategory = {},
                        onOpenEntry = { openedEntryId = it },
                        onBackToCategories = {},
                        onCloseEntry = {},
                        onCompare = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        waitForLearning()
        compose.onNodeWithTag("wiki-character-learning", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("wiki-character-learning-${knownSkill.groupPosition}-${knownSkill.position}",
            useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().performClick()
        assertEquals("Lv10", knownSkill.requirement)
        assertEquals(knownSkill.targetId, openedEntryId)
    }

    @Test fun characterLearningUsesCompactColumnsAndDistinctRankBadges() {
        val candidates = repository.page(WikiPageRequest(categoryId = "characters", limit = 100)).entries
        val selection = candidates.firstNotNullOfOrNull { character ->
            val skills = repository.characterLearning(character.id).skills
            val group = skills.groupBy(WikiCharacterSkill::groupPosition).toSortedMap().values
                .firstOrNull { it.size >= 2 } ?: return@firstNotNullOfOrNull null
            val rankedSkill = skills.firstOrNull { wikiLearningRank(it.requirement) != null }
                ?: return@firstNotNullOfOrNull null
            val gridSkills = group.sortedBy(WikiCharacterSkill::position).take(2)
            Triple(character, gridSkills, rankedSkill)
        } ?: error("Expected a character with grouped skills and a rank requirement")
        val (character, rowSkills, rankedSkill) = selection
        val ranks = listOf("E", "E+", "D", "D+", "C", "C+", "B", "B+", "A", "A+", "S", "S+")
        assertEquals(ranks.size, ranks.mapNotNull { wikiLearningRankTone(it, darkTheme = false)?.container }.distinct().size)
        assertEquals(ranks.size, ranks.mapNotNull { wikiLearningRankTone(it, darkTheme = true)?.container }.distinct().size)
        assertEquals("S+", wikiLearningRank(" s＋ "))
        assertNull(wikiLearningRank("Lv10"))

        compose.setContent {
            Box(Modifier.requiredWidth(400.dp).height(760.dp)) {
                CompositionLocalProvider(LocalContext provides context) {
                    MaterialTheme {
                        WikiCatalogPane(
                            repository = repository,
                            query = "",
                            selectedCategoryId = "characters",
                            selectedEntryId = character.id,
                            onQueryChange = {},
                            onOpenCategory = {},
                            onOpenEntry = {},
                            onBackToCategories = {},
                            onCloseEntry = {},
                            onCompare = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        waitForLearning()
        compose.onNodeWithTag("wiki-character-learning", useUnmergedTree = true).performScrollTo()
        compose.onNodeWithTag("structured-skill-matrix", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithTag(
            "wiki-character-learning-${rowSkills[0].groupPosition}-${rowSkills[0].position}",
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(
            "wiki-character-learning-${rowSkills[1].groupPosition}-${rowSkills[1].position}",
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()

        // All groups are directly visible without switching tabs
        val otherGroupSkill = repository.characterLearning(character.id).skills.first {
            it.groupPosition != rowSkills.first().groupPosition
        }
        compose.onNodeWithTag(
            "wiki-character-learning-${otherGroupSkill.groupPosition}-${otherGroupSkill.position}",
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag(
            "wiki-learning-rank-${wikiLearningRank(rankedSkill.requirement)}-${rankedSkill.groupPosition}-${rankedSkill.position}",
            useUnmergedTree = true,
        )
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun narrowLearningReturnPreservesGroupPositionAndSkillImage() = verifyLearningReturn(400, true)

    @Test fun expandedLearningReturnPreservesGroupPositionAndSkillImage() = verifyLearningReturn(1000, true)

    @Test fun learningReturnStartsAtTopWhenPreferenceIsDisabled() = verifyLearningReturn(400, false)

    private fun verifyLearningReturn(width: Int, restore: Boolean) {
        org.junit.Assume.assumeTrue(width <= context.resources.configuration.screenWidthDp)
        context.getSharedPreferences("personal-view", 0).edit().putBoolean(RESTORE_READING_POSITION, restore).commit()
        val learning = repository.characterLearning(characterId)
        val skill = learning.skills.first { it.groupPosition != learning.skills.first().groupPosition &&
            it.targetId != null && it.imageAsset != null }
        var selectedEntryId by mutableStateOf<String?>(characterId)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                ReadingPositionProvider {
                    Box(Modifier.requiredWidth(width.dp).height(600.dp)) {
                        MaterialTheme {
                            WikiCatalogScreen(repository, "", "characters", selectedEntryId, {}, {},
                                { selectedEntryId = it }, {}, { selectedEntryId = null })
                        }
                    }
                }
            }
        }
        waitForLearning()
        compose.onNodeWithTag("wiki-character-learning", useUnmergedTree = true).performScrollTo()
        val tag = "wiki-character-learning-${skill.groupPosition}-${skill.position}"
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        compose.waitUntil(15000) {
            compose.onAllNodesWithContentDescription("${skill.name}图标", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("${skill.name}图标", useUnmergedTree = true).assertIsDisplayed()
        val before = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitUntil(15000) { selectedEntryId == skill.targetId }
        compose.onNodeWithText("返回上一级").performClick()
        compose.waitUntil(15000) { selectedEntryId == characterId }
        compose.waitUntil(15000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        if (restore) {
            val after = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
            assertEquals("Returning should preserve the same reading offset", before, after, 2f)
        } else {
            compose.onNodeWithText("返回角色").assertIsDisplayed()
        }
    }

    @Test fun linkedEntryBackActionRestoresThePreviousDetail() {
        val skillId = repository.page(WikiPageRequest(categoryId = "skills", query = "战场剧作家"))
            .entries.first { it.title == "战场剧作家" }.id
        val learnedSkill = repository.characterLearning(characterId).skills.first { it.targetId == skillId }
        var selectedEntryId by mutableStateOf<String?>(characterId)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    WikiCatalogScreen(
                        repository = repository,
                        query = "",
                        selectedCategoryId = "characters",
                        selectedEntryId = selectedEntryId,
                        onQueryChange = {},
                        onOpenCategory = {},
                        onOpenEntry = { selectedEntryId = it },
                        onBackToCategories = {},
                        onCloseEntry = { selectedEntryId = null },
                    )
                }
            }
        }
        waitForLearning()
        compose.onNodeWithTag("wiki-character-learning-${learnedSkill.groupPosition}-${learnedSkill.position}",
            useUnmergedTree = true)
            .performScrollTo().performClick()
        compose.waitUntil(15000) { selectedEntryId == skillId }
        compose.onNodeWithText("返回上一级").performClick()
        compose.waitUntil(15000) { selectedEntryId == characterId }
        compose.waitUntil(15000) { compose.onAllNodesWithTag(
            "wiki-character-learning-${learnedSkill.groupPosition}-${learnedSkill.position}",
            useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wiki-character-learning-${learnedSkill.groupPosition}-${learnedSkill.position}",
            useUnmergedTree = true).assertIsDisplayed()
    }

    private fun waitForLearning() {
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("wiki-character-learning", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun wikiHistoryAndBrowseScrollSurviveDestinationChanges() {
        val entries = repository.page(WikiPageRequest(categoryId = "characters", limit = 100)).entries
        val target = entries.withIndex().drop(5).firstOrNull { repository.links(it.value.id).isNotEmpty() }?.value
            ?: entries.first { repository.links(it.id).isNotEmpty() }
        val linkedEntry = repository.links(target.id).first()
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
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                MaterialTheme { FireEmblemCompanionApp() }
            }
        }
        compose.onNodeWithText("图鉴").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("wiki-category-characters").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wiki-category-characters").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("角色").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("第 1–", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(target.title))
        compose.onNodeWithText(target.title).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("可学技能").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(linkedEntry.title).filterToOne(hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("返回上一级").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("攻略").performClick()
        compose.onNodeWithText("图鉴").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("返回上一级").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回上一级").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("可学技能").fetchSemanticsNodes().isNotEmpty() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(target.title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(target.title).assertIsDisplayed()
    }

    @Test fun relationshipsPreservePartialAndLongFieldValues() {
        val partialCondition = "完成前置任务后加入；另需与未关联的角色对话。"
        val longCondition = "达到指定等级并完成所有前置任务后，在第三部开始之前前往指定地点，与角色对话并选择邀请加入。"
        val original = requireNotNull(repository.entry(characterId))
        val linkedRepository = object : WikiRepository by repository {
            override fun entry(id: String): WikiEntry? = if (id == characterId) original.copy(
                fields = listOf(WikiField("加入条件", partialCondition), WikiField("特技效果", longCondition)),
            ) else repository.entry(id)

            override fun links(id: String): List<WikiLink> = listOf(
                WikiLink("skills:test-partial", "前置任务", "加入条件", "完成前置任务后加入"),
                WikiLink("skills:test-long", "关联特技", "特技效果", longCondition),
            )
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    WikiCatalogPane(
                        repository = linkedRepository,
                        query = "",
                        selectedCategoryId = "characters",
                        selectedEntryId = characterId,
                        onQueryChange = {},
                        onOpenCategory = {},
                        onOpenEntry = {},
                        onBackToCategories = {},
                        onCloseEntry = {},
                        onCompare = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("相关资料").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(partialCondition).performScrollTo()
        compose.onNodeWithText("特技与血印").performScrollTo()
        assertEquals(2, compose.onAllNodesWithText(longCondition).fetchSemanticsNodes().size)
    }

    @Test fun longWikiFactsRemainReadableInNarrowPaneWithLargeText() {
        val longSummary = "边境驻军长期守护王国北部关隘并协助周边城镇防御"
        val compactValue = "额外能力提升效果".repeat(3)
        val original = requireNotNull(repository.entry(characterId))
        val longFactRepository = object : WikiRepository by repository {
            override fun entry(id: String): WikiEntry? = if (id == characterId) original.copy(
                fields = listOf(WikiField("所属势力", longSummary), WikiField("附记", compactValue)),
            ) else repository.entry(id)

            override fun links(id: String): List<WikiLink> = emptyList()
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                Box(Modifier.width(360.dp).height(760.dp)) {
                    MaterialTheme {
                        WikiCatalogPane(
                            repository = longFactRepository,
                            query = "",
                            selectedCategoryId = "characters",
                            selectedEntryId = characterId,
                            onQueryChange = {},
                            onOpenCategory = {},
                            onOpenEntry = {},
                            onBackToCategories = {},
                            onCloseEntry = {},
                            onCompare = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("wiki-hero-fact-所属势力").fetchSemanticsNodes().isNotEmpty()
        }
        val summaryHeight = compose.onNodeWithTag("wiki-hero-fact-所属势力")
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue("Long summary facts should wrap instead of clipping", summaryHeight > 60f)

        compose.onNodeWithText("更多资料 · 1 项").performScrollTo().performClick()
        compose.onNodeWithTag("wiki-compact-fact-附记").performScrollTo()
        val compactHeight = compose.onNodeWithTag("wiki-compact-fact-附记")
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue("Large text should expand compact facts rather than ellipsize", compactHeight > 150f)
    }

    @Test fun guideCategoryLinksClearAnEarlierWikiSearch() {
        val firstCharacter = repository.page(WikiPageRequest(categoryId = "characters")).entries.first().title
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
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                MaterialTheme { FireEmblemCompanionApp() }
            }
        }
        compose.onNodeWithText("图鉴").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("搜索图鉴").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("搜索图鉴").performTextReplacement("不存在的检索内容")
        compose.waitUntil(15000) { compose.onAllNodesWithText("没有找到匹配的图鉴条目。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("攻略").performClick()
        compose.onNodeWithText("角色图鉴").performScrollTo()
        val guidePosition = compose.onNodeWithText("角色图鉴").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("角色图鉴").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText(firstCharacter).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("不存在的检索内容").assertDoesNotExist()
        compose.onNodeWithText("没有找到匹配的图鉴条目。").assertDoesNotExist()
        compose.onNodeWithText(firstCharacter).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("返回角色").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回角色").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("第 1–", substring = true).fetchSemanticsNodes().isNotEmpty() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15000) { compose.onAllNodesWithText("角色图鉴").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(guidePosition, compose.onNodeWithText("角色图鉴").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test fun personalBookmarkReturnsToItsOriginalReadingPosition() {
        PersonalStore(context).edit(UUID.randomUUID().toString(), "bookmark", buildJsonObject {
            put("profileId", "global")
            put("name", "测试收藏")
            put("wikiId", characterId)
            put("favorite", true)
            put("completed", false)
            put("note", "")
        }, expectedParents = emptySet())
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
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                MaterialTheme { FireEmblemCompanionApp() }
            }
        }
        compose.onNodeWithText("我的").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("测试收藏").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("测试收藏")[0].performScrollTo()
        compose.onNodeWithText("查看资料").performScrollTo()
        val before = compose.onAllNodesWithText("测试收藏")[0].fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("查看资料").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("返回我的记录").fetchSemanticsNodes().isNotEmpty() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(15000) { compose.onAllNodesWithText("测试收藏").fetchSemanticsNodes().isNotEmpty() }
        val after = compose.onAllNodesWithText("测试收藏")[0].assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertEquals("Returning from a saved Wiki entry should preserve the personal page", before, after, 2f)
    }

    @Test fun professionDetailsUseGameplaySectionsInsteadOfRawFieldList() {
        val professionId = repository.page(WikiPageRequest(categoryId = "professions", query = "德鲁伊"))
            .entries.first { it.title == "德鲁伊" }.id
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    WikiCatalogPane(
                        repository = repository,
                        query = "",
                        selectedCategoryId = "professions",
                        selectedEntryId = professionId,
                        onQueryChange = {},
                        onOpenCategory = {},
                        onOpenEntry = {},
                        onBackToCategories = {},
                        onCloseEntry = {},
                        onCompare = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("职业特点").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("职业要求").fetchSemanticsNode()
        compose.onNodeWithText("成长修正").fetchSemanticsNode()
        compose.onNodeWithText("基础能力").fetchSemanticsNode()
        assertTrue(compose.onAllNodesWithText("资料一览").fetchSemanticsNodes().isEmpty())
    }

    @Test fun guideCardsOpenTheCharacterEncyclopedia() {
        var openedCategory: String? = null
        compose.setContent {
            MaterialTheme {
                GuidesScreen(atlasState = AtlasLibraryState.Ready(repository), onOpenWiki = { openedCategory = it })
            }
        }
        compose.onNodeWithText("主线世界地图").fetchSemanticsNode()
        compose.onNodeWithText("外传").fetchSemanticsNode()
        compose.onNodeWithText("支线任务").fetchSemanticsNode()
        compose.onNodeWithText("主线世界地图").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("世界地图").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回攻略").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("路线与任务").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("四线任务清单").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("路线任务").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("凯伊篇").assertIsDisplayed()
        compose.onNodeWithText("返回攻略").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("路线与任务").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("角色图鉴").performScrollTo().performClick()
        compose.waitUntil(15000) { openedCategory == "characters" }
    }

    @Test fun wikiBrowserUsesSplitLayoutWhenWindowIsExpanded() {
        var windowWidthDp = 0
        compose.setContent {
            windowWidthDp = LocalConfiguration.current.screenWidthDp
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    WikiCatalogScreen(
                        repository = repository,
                        query = "",
                        selectedCategoryId = null,
                        selectedEntryId = null,
                        onQueryChange = {},
                        onOpenCategory = {},
                        onOpenEntry = {},
                        onBackToCategories = {},
                        onCloseEntry = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        if (windowWidthDp >= 720) {
            val category = compose.onNodeWithTag("wiki-category-${repository.catalog.categories.first().id}")
                .fetchSemanticsNode().boundsInRoot
            val detail = compose.onNodeWithText("离线图鉴").fetchSemanticsNode().boundsInRoot
            assertTrue("The detail pane must sit beside the catalog", detail.left >= category.right)
        } else {
            assertTrue(compose.onAllNodesWithText("离线图鉴").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun missingWikiImageHasAnExplicitFallbackLabel() {
        compose.setContent {
            MaterialTheme {
                WikiImage(path = null, description = "测试图片", modifier = Modifier.size(88.dp), crop = false)
            }
        }
        assertTrue(compose.onAllNodesWithText("暂无图片").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun unavailableWikiImageHasADistinctFallbackLabel() {
        compose.setContent {
            MaterialTheme {
                WikiImage(path = "missing-test-image.webp", description = "测试图片", modifier = Modifier.size(88.dp), crop = false)
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("图片暂不可用").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun categoryCardsUseTwoColumnsOnPhoneWidths() {
        val categories = repository.catalog.categories.take(2)
        compose.setContent {
            Box(Modifier.width(400.dp).height(760.dp)) {
                CompositionLocalProvider(LocalContext provides context) {
                    MaterialTheme {
                        WikiCatalogPane(
                            repository = repository,
                            query = "",
                            selectedCategoryId = null,
                            selectedEntryId = null,
                            onQueryChange = {},
                            onOpenCategory = {},
                            onOpenEntry = {},
                            onBackToCategories = {},
                            onCloseEntry = {},
                            onCompare = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.waitUntil(15000) {
            categories.all { compose.onAllNodesWithTag("wiki-category-${it.id}").fetchSemanticsNodes().isNotEmpty() }
        }
        val firstTop = compose.onNodeWithTag("wiki-category-${categories[0].id}").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithTag("wiki-category-${categories[1].id}").fetchSemanticsNode().boundsInRoot.top
        assertEquals("Phone-width category cards should share a row", firstTop, secondTop, 1f)
    }

    @Test fun categoryCardsUseAdditionalColumnsOnWiderPanes() {
        val categories = repository.catalog.categories.take(2)
        compose.setContent {
            Box(Modifier.requiredWidth(520.dp).height(760.dp)) {
                CompositionLocalProvider(LocalContext provides context) {
                    MaterialTheme {
                        WikiCatalogPane(
                            repository = repository,
                            query = "",
                            selectedCategoryId = null,
                            selectedEntryId = null,
                            onQueryChange = {},
                            onOpenCategory = {},
                            onOpenEntry = {},
                            onBackToCategories = {},
                            onCloseEntry = {},
                            onCompare = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.waitUntil(15000) {
            categories.all { compose.onAllNodesWithTag("wiki-category-${it.id}").fetchSemanticsNodes().isNotEmpty() }
        }
        val firstTop = compose.onNodeWithTag("wiki-category-${categories[0].id}").fetchSemanticsNode().boundsInRoot.top
        val secondTop = compose.onNodeWithTag("wiki-category-${categories[1].id}").fetchSemanticsNode().boundsInRoot.top
        assertEquals("Wider category panes should share a row", firstTop, secondTop, 1f)
    }

    @Test fun abilityCategoriesUseDistinctVisualCues() {
        assertNotEquals(categoryIcon("技能"), categoryIcon("特技"))
        assertNotEquals(categoryIcon("特技"), categoryIcon("魔法"))
        assertNotEquals(categoryIcon("魔法"), categoryIcon("战技"))
    }

    @Test fun characterRouteRenownSortAndFilterSurviveDetailBack() {
        var selectedCategoryId by androidx.compose.runtime.mutableStateOf<String?>("characters")
        var selectedEntryId by androidx.compose.runtime.mutableStateOf<String?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    WikiCatalogScreen(
                        repository = repository,
                        query = "",
                        selectedCategoryId = selectedCategoryId,
                        selectedEntryId = selectedEntryId,
                        onQueryChange = {},
                        onOpenCategory = { selectedCategoryId = it; selectedEntryId = null },
                        onOpenEntry = { selectedEntryId = it },
                        onBackToCategories = { selectedCategoryId = null; selectedEntryId = null },
                        onCloseEntry = { selectedEntryId = null },
                    )
                }
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("wiki-route-chip-kai").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wiki-route-chip-kai").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("wiki-route-fame-desc").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wiki-route-fame-desc").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("名声 Lv.10", substring = true).fetchSemanticsNodes().isNotEmpty() }

        // Also select affiliation filter chip before entering character detail
        compose.onNodeWithText("蔷薇风暴").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("西洛可").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("西洛可").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("加入比较").fetchSemanticsNodes().isNotEmpty() }

        // Return to list and verify route, renown sort, AND faction filter are still preserved
        compose.onNodeWithText("返回角色").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("wiki-route-fame-desc").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("蔷薇风暴").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("西洛可").performScrollTo().assertIsDisplayed()
    }

    @Test fun characterOverviewStartsCollapsedWithExpandAllAndStructuredRecruitment() {
        val tiaraId = repository.page(WikiPageRequest(categoryId = "characters", query = "媞雅拉"))
            .entries.first { it.title == "媞雅拉" }.id
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    WikiCatalogPane(
                        repository = repository,
                        query = "",
                        selectedCategoryId = "characters",
                        selectedEntryId = tiaraId,
                        onQueryChange = {},
                        onOpenCategory = {},
                        onOpenEntry = {},
                        onBackToCategories = {},
                        onCloseEntry = {},
                        onCompare = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("wiki-detail-expand-all").fetchSemanticsNodes().isNotEmpty() }
        // In 全部总览, 加入与招募 starts collapsed so page stays concise until expanded
        compose.onNodeWithTag("wiki-collapse-recruitment").performScrollTo()
        assertTrue(compose.onAllNodesWithText("支付3000G", substring = true).fetchSemanticsNodes().isEmpty())

        // Expand all sections and verify structured 4-route matrix renders cleanly
        compose.onNodeWithTag("wiki-detail-expand-all").performScrollTo().performClick()
        compose.onAllNodesWithText("支付3000G", substring = true)[0].performScrollTo().assertIsDisplayed()
    }
}
