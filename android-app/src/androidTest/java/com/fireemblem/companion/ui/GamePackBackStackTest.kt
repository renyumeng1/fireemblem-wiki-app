package com.fireemblem.companion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.CompanionGame
import com.fireemblem.companion.content.GameDataV1Repository
import com.fireemblem.companion.content.GamePackPageRequest
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.ui.theme.FireEmblemTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GamePackBackStackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun clickingLinkedSkillInsideThreeHousesCharacterAndPressingBackReturnsToCharacter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = GameDataV1Repository.open(context, CompanionGame.THREE_HOUSES.id)
        try {
            // Find a character with at least one clickable linked skill in skillTracks
            val firstPage = repo.page(GamePackPageRequest(categoryId = "characters", offset = 0))
            val targetChar = firstPage.entries.firstNotNullOf { summary ->
                val detail = repo.entry(summary.id)
                val linkedSkill = detail?.characterWiki?.skillTracks
                    ?.flatMap { it.items }
                    ?.firstOrNull { it.targetEntryId != null }
                if (detail != null && linkedSkill != null) Triple(detail.summary.id, detail.summary.title, linkedSkill.name) else null
            }
            val (charId, charTitle, skillName) = targetChar

            compose.setContent {
                FireEmblemTheme {
                    var selectedCategory by remember { mutableStateOf<String?>("characters") }
                    var selectedEntry by remember { mutableStateOf<String?>(charId) }
                    GamePackCatalogScreen(
                        game = CompanionGame.THREE_HOUSES,
                        repository = repo,
                        query = "",
                        selectedCategoryId = selectedCategory,
                        selectedEntryId = selectedEntry,
                        onQueryChange = {},
                        onOpenCategory = { selectedCategory = it; selectedEntry = null },
                        onOpenEntry = { selectedEntry = it },
                        onBackToCategories = { selectedCategory = null; selectedEntry = null },
                        onCloseEntry = { selectedEntry = null },
                    )
                }
            }

            // Wait for character detail hub to load and switch to "可学技能" tab
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("可学技能").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("可学技能").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(skillName).fetchSemanticsNodes().isNotEmpty()
            }

            // Tap linked skill inside the skill matrix -> opens skill detail with "返回上一级" back label
            val skillInMatrix = hasText(skillName) and hasAnyAncestor(hasTestTag("structured-skill-matrix"))
            compose.onNodeWithTag("character-wiki-hub").performScrollToNode(skillInMatrix)
            compose.onNode(skillInMatrix).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("返回上一级").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("返回上一级").assertIsDisplayed().performClick()

            // Must land RIGHT BACK on the character detail (still on 可学技能 tab!), NOT the character list!
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(charTitle).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("可学技能").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(charTitle) and hasAnyAncestor(hasTestTag("character-wiki-hub"))).assertIsDisplayed()
            compose.onNodeWithTag("character-wiki-hub").assertIsDisplayed()
        } finally {
            repo.close()
        }
    }

    @Test
    fun allThreeGamesExposeAttackRangeAndRealInGameIcons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Three Houses: Hapi's magic spells link to full equipment stats + range
        val thRepo = GameDataV1Repository.open(context, CompanionGame.THREE_HOUSES.id)
        try {
            // 1. Three Houses: Hapi's magic, combat arts, personal skill & universal prowess ALL link with real icons & Chinese details
            val hapiSummary = thRepo.page(GamePackPageRequest(categoryId = "characters", query = "哈琵")).entries.first()
            val hapi = requireNotNull(thRepo.entry(hapiSummary.id))
            val hapiWiki = requireNotNull(hapi.characterWiki)
            assertNotNull("Hapi must have real personal skill icon", hapiWiki.personalSkillIconAsset)
            assertTrue(
                "Hapi must have Chinese personal skill description",
                hapiWiki.personalSkillDescription?.contains("魔物") == true,
            )
            val allHapiSkills = hapiWiki.skillTracks.flatMap { it.items }
            assertTrue(
                "All Hapi skill pills must be linked (unlinked=${allHapiSkills.filter { it.targetEntryId == null }.map { it.name }})",
                allHapiSkills.all { it.targetEntryId != null },
            )
            assertTrue(
                "All Hapi skill pills must carry real in-game FE16 icons",
                allHapiSkills.all { it.imageAsset != null },
            )

            val reasonTrack = hapiWiki.skillTracks.first { it.trackKey == "reason" }
            val miasma = reasonTrack.items.first { it.name == "瘴气Δ" }
            assertNotNull("瘴气Δ should carry range on skill pill", miasma.range)
            assertTrue("瘴气Δ should link to full equipment spell record", miasma.targetEntryId!!.startsWith("equipment:"))
            val miasmaDetail = requireNotNull(thRepo.entry(miasma.targetEntryId!!))
            assertNotNull("瘴气Δ must have real FE16 dark magic icon", miasmaDetail.summary.imageAsset)
            val miasmaFields = miasmaDetail.sections.flatMap { it.fields }
            assertNotNull("瘴气Δ detail must include 射程", miasmaFields.firstOrNull { it.label == "射程" })
            assertNotNull("瘴气Δ detail must include Chinese 效果", miasmaFields.firstOrNull { it.label == "效果" && it.value.any { c -> c in '\u4E00'..'\u9FFF' } })

            val faithTrack = hapiWiki.skillTracks.first { it.trackKey == "faith" }
            val physic = faithTrack.items.first { it.name == "远程回复" }
            assertNotNull("远程回复 should carry localized range", physic.range)
            assertTrue("远程回复 range should be localized to Chinese", physic.range!!.contains("魔力"))
            val physicDetail = requireNotNull(thRepo.entry(physic.targetEntryId!!))
            val physicEff = physicDetail.sections.flatMap { it.fields }.firstOrNull { it.label == "效果" }?.value.orEmpty()
            assertTrue("远程回复 effect must be Chinese, got: $physicEff", physicEff.contains("回复") && !physicEff.contains("Restores"))

            val banshee = reasonTrack.items.first { it.name == "报丧女妖Θ" }
            val bansheeDetail = requireNotNull(thRepo.entry(banshee.targetEntryId!!))
            val bansheeEff = bansheeDetail.sections.flatMap { it.fields }.firstOrNull { it.label == "效果" }?.value.orEmpty()
            assertTrue("报丧女妖Θ effect must be Chinese, got: $bansheeEff", bansheeEff.contains("移动力") && !bansheeEff.contains("Inflicts"))

            // Direct lookup in `skills` category for combat arts (曲射), mastery abilities (鬼神一击), and magic (远程回复 / 报丧女妖Θ)
            val curvedShot = thRepo.page(GamePackPageRequest(categoryId = "skills", query = "曲射")).entries.first { it.title == "曲射" }
            assertNotNull("曲射 must have real FE16 bow combat art icon", curvedShot.imageAsset)
            val csDetail = requireNotNull(thRepo.entry(curvedShot.id))
            val csFields = csDetail.sections.flatMap { it.fields }
            assertTrue("曲射 must expose 射程 2～3", csFields.any { it.label == "射程" && it.value == "2～3" })
            assertTrue("曲射 must expose 威力 +1", csFields.any { it.label == "威力" && it.value == "+1" })
            assertTrue("曲射 must expose 命中 +30", csFields.any { it.label == "命中" && it.value == "+30" })
            assertTrue("曲射 must expose Chinese 战技效果", csFields.any { it.label == "战技效果" && it.value.contains("飞行") })
            assertTrue("曲射 must have no boilerplate stub text", csFields.none { it.value.contains("收录") })

            val deathBlow = thRepo.page(GamePackPageRequest(categoryId = "skills", query = "鬼神一击")).entries.first { it.title == "鬼神一击" }
            assertNotNull("鬼神一击 must have real FE16 skill icon", deathBlow.imageAsset)
            val dbFields = requireNotNull(thRepo.entry(deathBlow.id)).sections.flatMap { it.fields }
            assertTrue("鬼神一击 must expose Chinese 特技效果", dbFields.any { it.label == "特技效果" && it.value.contains("力量") })

            val skillBanshee = thRepo.page(GamePackPageRequest(categoryId = "skills", query = "报丧女妖")).entries.first { it.title == "报丧女妖Θ" }
            assertNotNull("skills:报丧女妖Θ must have real icon", skillBanshee.imageAsset)
            val sbFields = requireNotNull(thRepo.entry(skillBanshee.id)).sections.flatMap { it.fields }
            assertNotNull("skills:报丧女妖Θ must expose 射程", sbFields.firstOrNull { it.label == "射程" })
            assertTrue("skills:报丧女妖Θ must expose Chinese 魔法效果", sbFields.any { it.label == "魔法效果" && it.value.contains("移动力") })

            val skillPhysic = thRepo.page(GamePackPageRequest(categoryId = "skills", query = "远程回复")).entries.first()
            val skillPhysicDetail = requireNotNull(thRepo.entry(skillPhysic.id))
            val skillPhysicFields = skillPhysicDetail.sections.flatMap { it.fields }
            assertNotNull("skills:远程回复 must expose 射程", skillPhysicFields.firstOrNull { it.label == "射程" })
            assertTrue(
                "skills:远程回复 must expose Chinese 魔法效果",
                skillPhysicFields.any { it.label == "魔法效果" && it.value.contains("回复") },
            )
            val crests = thRepo.page(GamePackPageRequest(categoryId = "crests", limit = 40)).entries
            assertTrue("All 22 Three Houses crests must have real icons", crests.size == 22 && crests.all { it.imageAsset != null })
            val bylethSummary = thRepo.page(GamePackPageRequest(categoryId = "characters", query = "Byleth")).entries.first()
            val ingridSummary = thRepo.page(GamePackPageRequest(categoryId = "characters", query = "Ingrid")).entries.first()
            val thCharCat = thRepo.catalog.categories.first { it.id == "characters" }
            val thRecruitCat = thRepo.catalog.categories.first { it.id == "recruitment" }
            val thSupportsCat = thRepo.catalog.categories.first { it.id == "supports" }
            assertTrue(
                "3H characters category must use Byleth-M portrait (got ${thCharCat.iconAsset}, expected ${bylethSummary.imageAsset})",
                thCharCat.iconAsset != null && thCharCat.iconAsset == bylethSummary.imageAsset,
            )
            assertTrue(
                "3H supports category must use Ingrid portrait (got ${thSupportsCat.iconAsset}, expected ${ingridSummary.imageAsset})",
                thSupportsCat.iconAsset != null && thSupportsCat.iconAsset == ingridSummary.imageAsset,
            )
            assertTrue(
                "3H recruitment category must use Rhea portrait",
                thRecruitCat.iconAsset == "games/three-houses/assets/app/category_recruitment_rhea.webp",
            )
            context.assets.open(thRecruitCat.iconAsset!!).use { stream ->
                assertTrue("Rhea category portrait must be non-empty", stream.readBytes().isNotEmpty())
            }
        } finally {
            thRepo.close()
        }

        // 2. Engage: real skill icons (437/437), class pixel sprites (68/68), weapon-proficiency (8/8), clean bond-rings (492/492), weapon range
        val engRepo = GameDataV1Repository.open(context, CompanionGame.ENGAGE.id)
        try {
            val alearSummary = engRepo.page(GamePackPageRequest(categoryId = "characters", query = "琉尔")).entries.first()
            val alear = requireNotNull(engRepo.entry(alearSummary.id))
            assertNotNull("Engage Alear must have real personal skill icon", alear.characterWiki?.personalSkillIconAsset)
            assertTrue(
                "Engage skill matrix must populate real game icons",
                alear.characterWiki!!.skillTracks.flatMap { it.items }.any { it.imageAsset != null },
            )
            val engageClasses = engRepo.page(GamePackPageRequest(categoryId = "classes", limit = 40)).entries
            assertTrue("All Engage classes must have real FE17 pixel sprites", engageClasses.all { it.imageAsset != null })
            val engageProf = engRepo.page(GamePackPageRequest(categoryId = "weapon-proficiency", limit = 20)).entries
            assertTrue("All 8 Engage weapon-proficiency entries must have real icons", engageProf.size == 8 && engageProf.all { it.imageAsset != null })
            val engageSkills = engRepo.page(GamePackPageRequest(categoryId = "skills", limit = 40)).entries
            assertTrue("All Engage skills must have real icons and non-blank subtitles", engageSkills.all { it.imageAsset != null && it.subtitle.isNotBlank() })
            assertTrue("Engage skills must expose >= 5 skill type filter chips", engRepo.filterOptions("skills").size >= 5)
            val lunarPlus = engRepo.page(GamePackPageRequest(categoryId = "skills", query = "月之手镯+")).entries.first { it.title == "月之手镯+" }
            assertNotNull("月之手镯+ must have real FE17 skill icon", lunarPlus.imageAsset)
            val bondRingPage = engRepo.page(GamePackPageRequest(categoryId = "bond-rings", limit = 40))
            assertTrue("Bond rings should be cleanly 492 entries (got ${bondRingPage.totalMatches})", bondRingPage.totalMatches == 492)
            assertTrue("Bond rings must have portraits", bondRingPage.entries.all { it.imageAsset != null })

            val weapons = engRepo.page(GamePackPageRequest(categoryId = "weapons", limit = 20)).entries
            val withRange = weapons.count { w ->
                engRepo.entry(w.id)?.sections?.flatMap { it.fields }?.any { it.label == "射程" } == true
            }
            assertTrue("Engage weapons must surface 射程 ($withRange/${weapons.size})", withRange >= 15)
            assertTrue("Engage categories should carry real game icons", engRepo.catalog.categories.count { it.iconAsset != null } >= 10)
        } finally {
            engRepo.close()
        }

        // 3. Fortunes Weave: combat-arts & magic render 射程 directly in 战斗数据 (not buried!)
        val fwRepo = WikiDatabaseRepository.open(context)
        try {
            val magicEntry = fwRepo.page(WikiPageRequest(categoryId = "magic", query = "火焰")).entries.first()
            compose.setContent {
                FireEmblemTheme {
                    WikiCatalogPane(
                        repository = fwRepo,
                        query = "",
                        selectedCategoryId = "magic",
                        selectedEntryId = magicEntry.id,
                        onQueryChange = {},
                        onOpenCategory = {},
                        onOpenEntry = {},
                        onBackToCategories = {},
                        onCloseEntry = {},
                        onCompare = {},
                    )
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("战斗数据").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("射程").assertIsDisplayed()
            compose.onNodeWithText("1～2").assertIsDisplayed()
            assertTrue("FW categories should expose real game icons", fwRepo.catalog.categories.count { it.iconAsset != null } >= 10)
        } finally {
            fwRepo.close()
        }
    }

    @Test
    fun threeHousesFilterPreservedAfterDetailAndRichDetailsRenderCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val thRepo = GameDataV1Repository.open(context, CompanionGame.THREE_HOUSES.id)
        try {
            compose.setContent {
                FireEmblemTheme {
                    var selectedCat by remember { mutableStateOf<String?>("characters") }
                    var selectedEntry by remember { mutableStateOf<String?>(null) }
                    GamePackCatalogScreen(
                        game = CompanionGame.THREE_HOUSES,
                        repository = thRepo,
                        query = "",
                        selectedCategoryId = selectedCat,
                        selectedEntryId = selectedEntry,
                        onQueryChange = {},
                        onOpenCategory = { selectedCat = it; selectedEntry = null },
                        onOpenEntry = { selectedEntry = it },
                        onBackToCategories = { selectedCat = null; selectedEntry = null },
                        onCloseEntry = { selectedEntry = null },
                    )
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("game-pack-filter:青狮子学级").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("game-pack-filter:青狮子学级").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("亚修", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("亚修", substring = true).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("character-wiki-hub").fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodesWithText("关闭详情").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("关闭详情").performClick()
            } else {
                compose.onNodeWithText("返回角色").performClick()
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("game-pack-filter:青狮子学级").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("game-pack-filter:青狮子学级").assertIsSelected()
            compose.onAllNodesWithText("亚修", substring = true)[0].assertIsDisplayed()

            // Verify zero boilerplate categories and structured support dialogues / classes / battalions
            assertTrue(thRepo.catalog.categories.none { it.id == "growth" || it.id == "battles" })
            val supportEntry = thRepo.page(GamePackPageRequest(categoryId = "supports", query = "贝尔娜提塔")).entries.first()
            val detail = thRepo.entry(supportEntry.id)!!
            assertTrue("Support entry must include full Chinese dialogue lines", detail.supportConversations.isNotEmpty())
        } finally {
            thRepo.close()
        }
    }
}
