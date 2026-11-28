package com.fireemblem.companion.content

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameDataV1RepositoryTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "game-pack-repo-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun engagePackLoadsEmblemsBondRingsAndStripsWorkbookCells() {
        GameDataV1Repository.open(context, "engage").use { repo ->
            assertTrue(repo.catalog.categories.size >= 20)
            val emblems = repo.page(GamePackPageRequest(categoryId = "emblems", limit = 40))
            assertEquals(20, emblems.totalMatches)
            assertTrue(emblems.entries.all { it.imageAsset != null })
            val marth = emblems.entries.first { it.title == "马尔斯" }
            val marthDetail = requireNotNull(repo.entry(marth.id))
            assertTrue(marthDetail.sections.none { it.title == "工作表位置" })
            assertTrue(
                marthDetail.sections.flatMap { it.fields }.none {
                    it.value.trimStart().startsWith("{") || it.value.trimStart().startsWith("[")
                },
            )
            val classes = repo.page(GamePackPageRequest(categoryId = "classes", limit = 100))
            assertTrue(classes.totalMatches in 55..75)
            val nations = repo.page(GamePackPageRequest(categoryId = "country-investment", limit = 10))
            assertEquals(4, nations.totalMatches)
            assertTrue(nations.entries.all { it.imageAsset == null })
            val growthGuides = repo.page(GamePackPageRequest(categoryId = "growth-mechanics", limit = 10))
            assertEquals(3, growthGuides.totalMatches)
            assertTrue(growthGuides.entries.none { it.title.endsWith(".0") })
            val characters = repo.page(GamePackPageRequest(categoryId = "characters", limit = 60))
            assertTrue(characters.totalMatches <= 45)
            assertTrue(characters.entries.all { it.imageAsset != null })
            val fogato = characters.entries.first { it.title == "佛贾特" }
            val fogatoDetail = requireNotNull(repo.entry(fogato.id))
            val sectionTitles = fogatoDetail.sections.map { it.title }
            assertTrue("初始属性" in sectionTitles)
            assertTrue("成长率" in sectionTitles)
            assertTrue("能力上限" in sectionTitles)
            assertTrue("人物武装与特技" in sectionTitles)
            assertTrue("工作表位置" !in sectionTitles)
            val regionFilters = repo.filterOptions("characters")
            assertTrue(regionFilters.map { it.value }.containsAll(listOf("里特司", "费列聂", "布罗帝亚", "伊尔席翁", "索尔姆", "邪龙之章·其他")))
            val solmChars = repo.page(GamePackPageRequest(categoryId = "characters", filterGroup = "索尔姆", limit = 40))
            assertTrue(solmChars.entries.any { it.title == "佛贾特" })
            assertEquals(solmChars.totalMatches, solmChars.entries.size)
            val fogatoWiki = requireNotNull(fogatoDetail.characterWiki)
            assertEquals("索尔姆", fogatoWiki.affiliation)
            assertTrue(fogatoWiki.skillTracks.isNotEmpty())
            assertTrue(fogatoWiki.supportPartners.isNotEmpty())
            assertTrue(fogatoWiki.likedGifts.isNotEmpty())
            assertNotNull(repo.statBenchmarks.evaluate(StatMetricKind.GROWTH_RATE, "体格", "10%"))
            val chapters = repo.page(GamePackPageRequest(categoryId = "chapters", limit = 60))
            assertTrue(chapters.entries.all { entry -> entry.title.any { it in '\u4E00'..'\u9FFF' } })
            val weapons = repo.page(GamePackPageRequest(categoryId = "weapons", limit = 40))
            assertTrue(weapons.entries.all { it.imageAsset != null })
            val bondRings = repo.page(GamePackPageRequest(categoryId = "bond-rings", limit = 100))
            assertTrue(bondRings.entries.count { it.imageAsset != null } >= 90)
            val ratings = repo.page(GamePackPageRequest(categoryId = "community-skill-ratings", limit = 5))
            assertTrue(ratings.totalMatches > 10)
            val detail = requireNotNull(repo.entry(ratings.entries.first().id))
            assertTrue(detail.isCommunityOpinion)
            assertTrue(detail.images.isNotEmpty())
        }
        val dbFile = File(root, "game-packs/engage").listFiles()?.first { it.name.endsWith(".db") }
        assertNotNull(dbFile)
        SQLiteDatabase.openDatabase(requireNotNull(dbFile).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM workbook_cells", null).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test fun threeHousesPackLoadsRelationsImagesAndLocalizedSubtitles() {
        GameDataV1Repository.open(context, "three-houses").use { repo ->
            assertEquals(14, repo.catalog.categories.size)
            assertTrue(repo.catalog.categories.none { it.id == "growth" || it.id == "battles" })
            val characters = repo.page(GamePackPageRequest(categoryId = "characters", limit = 50))
            assertEquals(40, characters.entries.size)
            assertTrue(characters.entries.all { it.imageAsset != null })
            assertTrue(characters.entries.all { entry -> entry.title.any { it in '\u4E00'..'\u9FFF' } })
            assertTrue(characters.entries.any { it.title == "安娜" })
            assertTrue(characters.entries.any { it.title == "芙莲" })
            assertTrue(characters.entries.all { "初始职业：" in it.subtitle })
            val edelgard = characters.entries.first { it.titleEn == "Edelgard" }
            val edelgardDetail = requireNotNull(repo.entry(edelgard.id))
            assertTrue(edelgardDetail.images.size >= 2)
            val edelSections = edelgardDetail.sections.map { it.title }
            assertTrue("初始属性" in edelSections)
            assertTrue("成长率" in edelSections)
            assertTrue("人物武装与特技" in edelSections)
            assertTrue(edelgardDetail.sections.flatMap { it.fields }.any { it.label == "初始武器与道具" && it.value.isNotBlank() })
            val houseFilters = repo.filterOptions("characters")
            assertTrue(houseFilters.map { it.value }.containsAll(listOf("黑鹫学级", "青狮子学级", "金鹿学级", "灰狼学级", "赛罗司圣教会", "主角与独立")))
            val blueLions = repo.page(GamePackPageRequest(categoryId = "characters", filterGroup = "青狮子学级", limit = 20))
            assertEquals(8, blueLions.totalMatches)
            val ashe = blueLions.entries.first { it.titleEn == "Ashe" }
            val asheDetail = requireNotNull(repo.entry(ashe.id))
            val asheWiki = requireNotNull(asheDetail.characterWiki)
            assertEquals("青狮子学级", asheWiki.affiliation)
            assertTrue(asheWiki.skillTracks.size >= 10)
            assertTrue(asheWiki.skillTracks.any { it.trackKey == "bow" && it.items.any { item -> item.name == "狙击" } })
            assertTrue(asheWiki.supportPartners.isNotEmpty())
            assertTrue(asheWiki.likedGifts.isNotEmpty())
            assertTrue(asheWiki.likedMeals.isNotEmpty())
            assertEquals(2, asheWiki.lectureQuestions.size)
            assertTrue(asheWiki.lectureQuestions.all { q -> q.options.any { it.recommended && it.askerLikes } })
            assertNotNull(asheWiki.teaParty)
            assertTrue(requireNotNull(asheWiki.teaParty).favoriteTeas.isNotEmpty())
            assertTrue(requireNotNull(asheWiki.teaParty).finalComments.size >= 9)
            assertTrue(edelgardDetail.characterWiki?.lectureQuestions?.size == 2)
            assertTrue(edelgardDetail.characterWiki?.adviceQuestions?.isNotEmpty() == true)
            assertTrue(edelgardDetail.characterWiki?.lostItems?.isNotEmpty() == true)

            val dlcChecks = listOf("尤里斯", "巴鲁塔札尔", "康丝坦洁", "哈琵", "伊艾里扎", "安娜")
            dlcChecks.forEach { namePrefix ->
                val dlcSummary = characters.entries.first { it.title.startsWith(namePrefix) }
                val dlcWiki = requireNotNull(requireNotNull(repo.entry(dlcSummary.id)).characterWiki)
                val expectedLectures = if (namePrefix == "伊艾里扎") 0 else 2
                assertEquals(expectedLectures, dlcWiki.lectureQuestions.size)
                val tp = requireNotNull(dlcWiki.teaParty)
                assertTrue(tp.favoriteTeas.isNotEmpty())
                assertTrue(tp.interestedTopics.isNotEmpty())
                if (namePrefix == "康丝坦洁") {
                    assertEquals(18, tp.finalComments.size)
                    assertEquals(9, tp.finalComments.count { it.variant == "室内（日光外）" })
                    assertEquals(9, tp.finalComments.count { it.variant == "室外（日光下）" })
                } else {
                    assertTrue(tp.finalComments.size >= 9)
                }
            }

            val socialFilters = repo.filterOptions("social")
            assertTrue(socialFilters.any { it.value == "atype:茶会指南" && it.count == 40 })
            val teaEntries = repo.page(GamePackPageRequest(categoryId = "social", filterGroup = "atype:茶会指南", limit = 50))
            assertEquals(40, teaEntries.totalMatches)
            assertTrue(teaEntries.entries.all { it.imageAsset != null })
            val rheaTea = requireNotNull(repo.entry("social:tea-guide-rhea"))
            assertNotNull(rheaTea.summary.imageAsset)
            val rheaGuide = requireNotNull(rheaTea.teaPartyGuide)
            assertTrue(rheaGuide.favoriteTeas.isNotEmpty())
            assertTrue(rheaGuide.finalComments.size >= 9)
            assertNotNull(repo.statBenchmarks.evaluate(StatMetricKind.GROWTH_RATE, "速度", "50%"))
            assertNotNull(repo.statBenchmarks.evaluate(StatMetricKind.BASE_STAT, "HP", "23"))
            assertTrue(repo.filterOptions("classes").isNotEmpty())
            assertNotNull(context.assets.open(requireNotNull(edelgard.imageAsset)).use { android.graphics.BitmapFactory.decodeStream(it) })
            val classes = repo.page(GamePackPageRequest(categoryId = "classes", limit = 100))
            assertTrue(classes.entries.count { it.imageAsset != null } >= 60)
            assertTrue(classes.entries.all { entry -> entry.title.any { it in '\u4E00'..'\u9FFF' } })
            val chapters = repo.page(GamePackPageRequest(categoryId = "chapters", limit = 50))
            assertTrue(chapters.totalMatches > 20)
            assertTrue(chapters.entries.all { entry -> entry.title.any { it in '\u4E00'..'\u9FFF' } })
            val chapterDetail = requireNotNull(repo.entry(chapters.entries.first().id))
            assertEquals("source_only", chapterDetail.summary.verificationStatus)
            assertNotNull(chapterDetail.summary.titleEn)
            assertTrue(chapterDetail.relations.isNotEmpty() || repo.catalog.sources.isNotEmpty())
            // Verify Three Houses skill effect_json has zero raw arrays / source_row dumps
            val skills = repo.page(GamePackPageRequest(categoryId = "skills", limit = 20))
            assertTrue(skills.entries.isNotEmpty())
            skills.entries.take(10).forEach { sk ->
                val skDetail = requireNotNull(repo.entry(sk.id))
                assertTrue(
                    skDetail.sections.flatMap { it.fields }.none {
                        it.value.trimStart().startsWith("[") || it.value.contains("source_row")
                    },
                )
            }
        }
    }
}
