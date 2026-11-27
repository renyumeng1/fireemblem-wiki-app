package com.fireemblem.companion.content

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.maps.MapRepository
import com.fireemblem.companion.content.SupportPairRequest
import com.fireemblem.companion.content.SupportRelationFilter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WikiDatabaseRepositoryTest {
    private lateinit var context: Context
    private lateinit var temporaryRoot: File

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        temporaryRoot = Files.createTempDirectory(target.cacheDir.toPath(), "wiki-test-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = File(temporaryRoot, "no-backup")
        }
    }

    @After
    fun tearDown() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun installsTheBundledDatabaseAndBrowsesEveryEntryThroughPages() {
        WikiDatabaseRepository.open(context).use { repository ->
            val categories = repository.catalog.categories
            assertTrue(categories.size >= 11)
            assertTrue(categories.sumOf(WikiCategorySummary::entryCount) >= 2000)
            assertTrue(repository.catalog.contentVersion.isNotBlank())
            for (category in categories) {
                val ids = mutableSetOf<String>()
                var offset = 0
                do {
                    val page = repository.page(WikiPageRequest(categoryId = category.id, offset = offset, limit = 37))
                    assertEquals(category.entryCount, page.totalMatches)
                    assertTrue(page.entries.isNotEmpty())
                    page.entries.forEach { entry -> assertTrue("Duplicate entity ${entry.id}", ids.add(entry.id)) }
                    offset += page.entries.size
                } while (page.hasNext)
                assertEquals(category.entryCount, ids.size)
            }
        }
    }

    @Test
    fun loadsSemanticDetailsChineseSearchAndOfflineImages() {
        WikiDatabaseRepository.open(context).use { repository ->
            val result = repository.page(WikiPageRequest(categoryId = "characters", query = "HP成长率"))
            assertTrue(result.totalMatches > 0)
            val first = result.entries.first()
            val detail = requireNotNull(repository.entry(first.id))
            assertTrue(detail.fields.any { it.label == "HP成长率" })
            assertTrue(detail.sourceRowNumber > 0)
            assertTrue(detail.sourceSheetName.isNotEmpty())
            assertNotNull(detail.imageAsset)
            context.assets.open("wiki/${detail.imageAsset}").use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                assertNotNull(bitmap)
                bitmap?.recycle()
            }
            assertEquals(0, repository.page(WikiPageRequest(query = "' OR 1=1 --")).totalMatches)
            assertNull(repository.entry("missing"))
        }
    }

    @Test
    fun loadsMealPreferencesBidirectionallyWithAllMealArtworkOffline() {
        WikiDatabaseRepository.open(context).use { repository ->
            val meals = repository.page(WikiPageRequest(categoryId = "meals", limit = 100)).entries
            assertEquals(12, meals.size)
            meals.forEach { meal ->
                val asset = requireNotNull(meal.imageAsset)
                context.assets.open("wiki/$asset").use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                    assertNotNull("Missing meal artwork for ${meal.title}", bitmap)
                    bitmap?.recycle()
                }
                assertNotNull(repository.mealPreferencesForMeal(meal.id))
            }

            val characters = repository.page(WikiPageRequest(categoryId = "characters", limit = 100)).entries
            val source = characters.asSequence()
                .mapNotNull { repository.mealPreferencesForCharacter(it.id) }
                .first { it.hasSourceData && it.preferences.isNotEmpty() }
            val preference = source.preferences.first()
            val reverse = requireNotNull(repository.mealPreferencesForMeal(preference.mealEntryId))
            assertTrue(reverse.any {
                it.characterId == preference.characterId &&
                    it.level == preference.level &&
                    it.sourceRowNumber == preference.sourceRowNumber &&
                    it.sourceColumn == preference.sourceColumn
            })
            assertEquals(MealPreferenceLevel.entries.toSet(),
                characters.flatMap { repository.mealPreferencesForCharacter(it.id)?.preferences.orEmpty() }
                    .map(MealPreferenceRecord::level).toSet())
        }
    }

    @Test
    fun loadsBirdDialoguesWithCharacterSearchReviewFiltersAndSourceGaps() {
        WikiDatabaseRepository.open(context).use { repository ->
            val all = requireNotNull(repository.birdDialogues(BirdDialogueRequest(limit = 100)))
            assertEquals(485, all.totalMatches)
            assertTrue(all.sourceNote.contains("KeenGamer"))
            assertTrue(all.sourceNote.contains("RaiderKing"))
            assertEquals(447, requireNotNull(repository.birdDialogues(BirdDialogueRequest(
                reviewFilter = BirdDialogueReviewFilter.MARKED_CORRECTED,
            ))).totalMatches)
            assertEquals(38, requireNotNull(repository.birdDialogues(BirdDialogueRequest(
                reviewFilter = BirdDialogueReviewFilter.UNMARKED,
            ))).totalMatches)
            assertEquals(40, requireNotNull(repository.birdDialogues(BirdDialogueRequest(
                characterGroup = "主角",
            ))).totalMatches)
            assertEquals(431, requireNotNull(repository.birdDialogues(BirdDialogueRequest(
                characterGroup = "可招募角色",
            ))).totalMatches)

            val characterSearch = requireNotNull(repository.birdDialogues(BirdDialogueRequest(
                query = "班迪兹", limit = 100,
            )))
            assertTrue(characterSearch.entries.isNotEmpty())
            assertTrue(characterSearch.entries.all { it.characterName == "班迪兹" })
            assertTrue(characterSearch.entries.all { it.characterEntryId != null && it.characterImageAsset != null })

            val partialSourceRecord = requireNotNull(repository.birdDialogues(BirdDialogueRequest(
                query = "上了年纪睡眠",
            ))).entries.single()
            assertEquals(61, partialSourceRecord.sourceRowNumber)
            assertEquals("班迪兹", partialSourceRecord.characterName)
            assertNull(partialSourceRecord.characterGroup)
            assertNull(partialSourceRecord.originalPrompt)
            assertNull(partialSourceRecord.originalReaction)
            assertTrue(partialSourceRecord.markedCorrected)
            assertEquals(partialSourceRecord, repository.birdDialogue(partialSourceRecord.entryId))
            assertTrue(repository.links(partialSourceRecord.entryId).any {
                it.targetId == partialSourceRecord.characterEntryId && it.title == "班迪兹"
            })

            val imageAsset = requireNotNull(partialSourceRecord.characterImageAsset)
            context.assets.open("wiki/$imageAsset").use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                assertNotNull(bitmap)
                bitmap?.recycle()
            }
        }
    }

    @Test
    fun loadsAllSupportPairsJobsBonusesDialogueConditionsAndOfflinePortraits() {
        WikiDatabaseRepository.open(context).use { repository ->
            val guide = requireNotNull(repository.supportGuide())
            assertEquals(64, guide.people.size)
            assertEquals(64, guide.people.count { it.matchStatus == SupportWikiMatchStatus.MATCHED })
            assertEquals(60, guide.jobs.size)
            assertEquals(57, guide.jobs.count { it.matchStatus == SupportWikiMatchStatus.MATCHED })
            assertEquals(listOf(0, 400, 1400, 2400, 3200), guide.supportThresholds)
            assertEquals(9, guide.statNames.size)
            assertEquals(8, guide.supportTypes.size)

            val allPairs = mutableListOf<SupportPairSummary>()
            var offset = 0
            do {
                val page = requireNotNull(repository.supportPairs(SupportPairRequest(offset = offset, limit = 100)))
                assertEquals(2016, page.totalMatches)
                assertTrue(page.entries.isNotEmpty())
                allPairs += page.entries
                offset += page.entries.size
            } while (offset < page.totalMatches)
            assertEquals(2016, allPairs.map(SupportPairSummary::pairId).toSet().size)
            assertEquals(298, allPairs.count(SupportPairSummary::hasCombinationBonus))
            assertEquals(238, allPairs.count { it.dialogueCount > 0 })
            assertEquals(1778, allPairs.count { it.dialogueCount == 0 })

            assertEquals(298, requireNotNull(repository.supportPairs(SupportPairRequest(
                filter = SupportRelationFilter.WITH_COMBINATION_BONUS,
            ))).totalMatches)
            assertEquals(238, requireNotNull(repository.supportPairs(SupportPairRequest(
                filter = SupportRelationFilter.WITH_DIALOGUES,
            ))).totalMatches)
            assertEquals(1778, requireNotNull(repository.supportPairs(SupportPairRequest(
                filter = SupportRelationFilter.WITHOUT_DIALOGUES,
            ))).totalMatches)
            val firstPersonName = allPairs.first().first.sourceName
            assertEquals(63, requireNotNull(repository.supportPairs(SupportPairRequest(
                characterName = firstPersonName,
            ))).totalMatches)
            assertEquals(0, requireNotNull(repository.supportPairs(SupportPairRequest(query = "%_"))).totalMatches)

            val pairWithDialogue = allPairs.first { it.dialogueCount > 0 }
            val detail = requireNotNull(repository.supportPair(pairWithDialogue.pairId))
            assertEquals(pairWithDialogue, detail.summary)
            assertEquals(9, detail.combinationBonus.values.size)
            assertEquals(pairWithDialogue.dialogueCount, detail.dialogues.size)
            assertTrue(detail.dialogues.all { it.rank.minimumSupportLevel == it.rank.sourceRank + 1 })
            assertTrue(detail.dialogues.all { it.routeId in setOf(0, 1, 2, 4) })
            assertTrue(detail.sourceRevision.isNotBlank())

            val imagePaths = guide.people.mapNotNull(SupportPersonSummary::imageAsset).toSet() +
                guide.jobs.mapNotNull(SupportJob::imageAsset)
            imagePaths.forEach { path ->
                context.assets.open("wiki/$path").use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                    assertNotNull("Missing support artwork $path", bitmap)
                    bitmap?.recycle()
                }
            }
        }
    }

    @Test
    fun supportQueriesAreUnavailableForSchemaSevenDatabases() {
        withBundledDatabaseVersion(7) { repository ->
            assertNull(repository.supportGuide())
            assertNull(repository.supportPairs(SupportPairRequest()))
            assertNull(repository.supportPair("support-pair:not-in-this-schema"))
        }
    }

    @Test
    fun plantingGuideIsUnavailableForSchemaSixDatabases() {
        withBundledDatabaseVersion(6) { repository ->
            assertNull(repository.plantingGuide())
        }
    }

    @Test
    fun loadsPlantingGuideWithExactOfflineItemsAndMapPins() {
        WikiDatabaseRepository.open(context).use { repository ->
            val guide = requireNotNull(repository.plantingGuide())
            assertEquals(27, guide.locations.size)
            assertEquals(16, guide.plants.size)
            assertEquals(4, guide.fertilizers.size)
            assertEquals(6, guide.harvest.cycleTurns)
            assertEquals(10, guide.harvest.yieldCount)
            assertEquals(PlantingVerificationStatus.UNVERIFIED, guide.harvest.yieldCountStatus)
            assertEquals(listOf(100.0, 70.0, 40.0, 10.0, 0.0, 0.0),
                guide.harvestProbabilities.map(PlantingHarvestProbability::probabilityPercent))
            assertTrue(guide.harvestProbabilities.all {
                it.verificationStatus == PlantingVerificationStatus.UNVERIFIED
            })
            assertTrue(guide.harvest.probabilityNote.contains("尚未验证"))
            assertTrue(guide.harvest.fertilizerNote.contains("不能直接相加"))
            assertTrue(guide.plants.all {
                it.matchStatus == PlantingItemMatchStatus.MATCHED && it.itemEntryId != null && it.imageAsset != null
            })
            assertTrue(guide.fertilizers.all {
                it.matchStatus == PlantingItemMatchStatus.MATCHED && it.itemEntryId != null && it.imageAsset != null
            })

            val markers = guide.locations.flatMap(PlantingLocation::mapMarkers) +
                guide.fertilizers.flatMap(PlantingFertilizer::mapMarkers)
            assertEquals(80, markers.size)
            assertEquals(80, markers.map(PlantingMapMarker::sourceMapId).toSet().size)
            assertEquals(44, guide.locations.sumOf { it.mapMarkers.size })
            assertEquals(36, guide.fertilizers.sumOf { it.mapMarkers.size })
            val mapPoints = MapRepository.open(context).use { mapRepository ->
                mapRepository.chapters.flatMap { chapter ->
                    mapRepository.points(chapter.id).map { chapter.id to it.id }
                }.toSet()
            }
            assertTrue(markers.all { (it.chapterId to it.sourceMapId) in mapPoints })

            (guide.plants.mapNotNull(PlantingPlant::imageAsset) +
                guide.fertilizers.mapNotNull(PlantingFertilizer::imageAsset)).distinct().forEach { asset ->
                context.assets.open("wiki/$asset").use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                    assertNotNull("Missing planting artwork $asset", bitmap)
                    bitmap?.recycle()
                }
            }
        }
    }

    @Test
    fun findsSourceTrioRegardlessOfSelectionOrderAndRejectsRepeatedMembers() {
        WikiDatabaseRepository.open(context).use { repository ->
            fun characterId(name: String) = repository.page(
                WikiPageRequest(categoryId = "characters", query = name),
            ).entries.single { it.title == name }.id

            val first = characterId("丹提")
            val second = characterId("哥莱亚斯")
            val third = characterId("杰斯塔")
            val trio = requireNotNull(repository.diningTrio(listOf(first, second, third)))
            assertEquals("A", trio.relationship12)
            assertEquals("A", trio.relationship13)
            assertEquals("A", trio.relationship23)
            assertEquals("A", trio.minimumLevel)
            assertEquals(setOf(first, second, third), setOf(trio.first.characterId, trio.second.characterId, trio.third.characterId))
            assertEquals(trio, repository.diningTrio(listOf(third, first, second)))
            assertNull(repository.diningTrio(listOf(first, second, "characters:missing")))
            assertThrows(IllegalArgumentException::class.java) {
                repository.diningTrio(listOf(first, first, third))
            }
        }
    }

    @Test
    fun accessoriesUseEquipmentFieldsInsteadOfWeaponColumnLabels() {
        WikiDatabaseRepository.open(context).use { repository ->
            val shield = repository.page(WikiPageRequest(categoryId = "accessories", query = "木制盾"))
                .entries.first { it.title == "木制盾" }
            val detail = requireNotNull(repository.entry(shield.id))
            assertEquals("2", detail.fields.first { it.label == "重量" }.value)
            assertEquals("防守+1", detail.fields.first { it.label == "其他效果" }.value)
            assertFalse(detail.fields.any { it.label == "等级" || it.label == "耐久" })
        }
    }

    @Test
    fun repairsDamagedInstalledContentWithoutChangingPersonalFiles() {
        val sentinel = File(temporaryRoot, "personal-record.json").apply { writeText("preserve-me") }
        WikiDatabaseRepository.open(context).close()
        val checksum = context.assets.open("wiki/content.sha256").bufferedReader().use { it.readText().trim() }
        val installed = File(context.noBackupFilesDir, "wiki-databases/content-$checksum.db")
        installed.writeText("damaged")
        WikiDatabaseRepository.open(context).use { repository ->
            assertTrue(repository.catalog.categories.isNotEmpty())
            assertNull(repository.catalog.updateNotice)
        }
        assertEquals("preserve-me", sentinel.readText())
    }

    @Test
    fun includesRouteSpecificCatalogsWithTheirOwnBoundaries() {
        WikiDatabaseRepository.open(context).use { repository ->
            assertEquals(20, repository.page(WikiPageRequest(categoryId = "mounts")).totalMatches)
            assertEquals(14, repository.page(WikiPageRequest(categoryId = "drinks")).totalMatches)
            assertTrue(repository.page(WikiPageRequest(categoryId = "battalions")).totalMatches > 40)
            val mount = repository.page(WikiPageRequest(categoryId = "mounts", query = "野生马")).entries.first()
            val detail = requireNotNull(repository.entry(mount.id))
            assertTrue(detail.fields.any { it.label == "捕捉地点" })
            assertTrue(repository.links(mount.id).any { it.label == "满级放生装备" })
        }
    }

    @Test
    fun filtersAndSortsTypedFieldsAndRejectsUntrustedIdentifiers() {
        WikiDatabaseRepository.open(context).use { repository ->
            val options = repository.options("characters")
            val filter = options.first { it.key == "gender" }
            val value = filter.values.first()
            val page = repository.page(WikiPageRequest(categoryId = "characters", filterField = filter.key, filterValue = value, limit = 100))
            assertTrue(page.entries.isNotEmpty())
            assertTrue(page.entries.all { repository.entry(it.id)!!.fields.any { field -> field.label == filter.label && field.value == value } })
            val sort = options.first { it.numeric }
            val sorted = repository.page(WikiPageRequest(categoryId = "characters", sortField = sort.key, descending = true, limit = 100))
            val numbers = sorted.entries.mapNotNull { entry -> repository.entry(entry.id)!!.fields.firstOrNull { it.label == sort.label }
                ?.value?.removeSuffix("%")?.toDoubleOrNull() }
            assertEquals(numbers.sortedDescending(), numbers)
            assertThrows(IllegalArgumentException::class.java) { repository.page(WikiPageRequest(categoryId = "characters", sortField = "x);DROP TABLE wiki_entries;--")) }
            assertEquals(0, repository.page(WikiPageRequest(categoryId = "characters", filterField = "gender", filterValue = "' OR 1=1 --")).totalMatches)
        }
    }

    @Test
    fun navigatesForgeMaterialsAndBidirectionalLinks() {
        WikiDatabaseRepository.open(context).use { repository ->
            val weapon = repository.page(WikiPageRequest(categoryId = "weapons", query = "木制剑+1")).entries.first { it.title == "木制剑+1" }
            val links = repository.links(weapon.id)
            val base = links.first { it.title == "木制剑" }
            assertTrue(links.any { it.title == "锻造石" })
            assertTrue(repository.links(base.targetId).any { it.targetId == weapon.id })
            assertTrue(repository.links("missing").isEmpty())
        }
    }

    @Test
    fun loadsCharacterLearningGroupsConditionsAndUnavailableCharacters() {
        WikiDatabaseRepository.open(context).use { repository ->
            val character = repository.page(WikiPageRequest(categoryId = "characters", query = "凯伊"))
                .entries.first { it.title == "凯伊" }
            val learning = repository.characterLearning(character.id)
            val knownSkill = learning.skills.first { it.name == "黑炎涡流" }
            assertTrue(learning.hasSourceData)
            assertEquals("ps06wo", learning.sourceSheetId)
            assertTrue(learning.sourceRowNumber!! > 0)
            assertEquals("个人/圣焰", knownSkill.groupLabel)
            assertEquals("Lv10", knownSkill.requirement)
            assertNotNull(knownSkill.targetId)
            learning.skills.forEach { skill ->
                assertEquals(skill.targetId?.let { repository.entry(it)?.imageAsset }, skill.imageAsset)
            }
            assertTrue(learning.skills.any { it.imageAsset != null })
            assertTrue(repository.links(knownSkill.targetId!!).any { it.targetId == character.id })

            val characterWithoutSource = repository.page(WikiPageRequest(categoryId = "characters", query = "苏谛斯"))
                .entries.first { it.title == "苏谛斯" }
            assertFalse(repository.characterLearning(characterWithoutSource.id).hasSourceData)
        }
    }

    @Test
    fun loadsCompleteEndingStoriesByTypeAndExactCharacterRelations() {
        WikiDatabaseRepository.open(context).use { repository ->
            val category = repository.catalog.categories.single { it.id == "endings" }
            assertEquals(206, category.entryCount)
            assertEquals(82, repository.page(WikiPageRequest(
                categoryId = "endings", filterField = "ending_kind", filterValue = "双人结局", limit = 100,
            )).totalMatches)
            assertEquals(124, repository.page(WikiPageRequest(
                categoryId = "endings", filterField = "ending_kind", filterValue = "单人结局", limit = 100,
            )).totalMatches)

            val endingSummary = repository.page(WikiPageRequest(
                categoryId = "endings", query = "2万大军", limit = 100,
            )).entries.single()
            val ending = repository.entry(endingSummary.id)!!
            assertEquals("fireemblem.top · 结局一览", ending.sourceSheetName)
            assertTrue(ending.fields.any { it.label == "后日谈正文" && it.value.contains("2万大军") })
            assertTrue(repository.links(ending.id).any { it.label == "相关角色" && it.title == "凯伊" })
        }
    }

    @Test
    fun invalidUpgradeFallsBackToLastValidatedDatabaseWithANotice() {
        val previousVersion = WikiDatabaseRepository.open(context).use { it.catalog.contentVersion }
        val invalidDatabase = "not a database".toByteArray()
        val invalidHash = MessageDigest.getInstance("SHA-256").digest(invalidDatabase)
            .joinToString("") { byte -> "%02x".format(byte) }
        val source = WikiAssetSource { name ->
            when (name) {
                "content.db" -> invalidDatabase.inputStream()
                "content.sha256" -> invalidHash.byteInputStream()
                else -> error("Unexpected asset")
            }
        }
        WikiDatabaseRepository.open(context, source).use { repository ->
            assertEquals(previousVersion, repository.catalog.contentVersion)
            assertNotNull(repository.catalog.updateNotice)
            assertTrue(repository.page(WikiPageRequest(categoryId = "characters")).entries.isNotEmpty())
        }
        WikiDatabaseRepository.open(context).use { repository -> assertNull(repository.catalog.updateNotice) }
    }

    @Test
    fun invalidInitialInstallDoesNotCreateAnActiveDatabase() {
        assertThrows(WikiDatabaseException::class.java) {
            WikiDatabaseRepository.open(context) { "invalid".byteInputStream() }
        }
        assertFalse(File(context.noBackupFilesDir, "wiki-databases/active.sha256").exists())
    }

    @Test
    fun apkDoesNotContainTheRawTablesAsAnApplicationDataSource() {
        assertFalse(context.assets.list("")!!.contains("tencent-sheet"))
        val wikiAssets = context.assets.list("wiki")!!.toSet()
        assertTrue("content.db" in wikiAssets)
        assertTrue("content.sha256" in wikiAssets)
        assertFalse("tables" in wikiAssets)
    }

    @Test
    fun restorationGuideKeepsRequirementsExactLinksImagesAndSourceGaps() {
        WikiDatabaseRepository.open(context).use { repository ->
            val guide = requireNotNull(repository.restorationGuide())
            assertEquals(47, guide.locations.size)
            assertEquals(55, guide.materials.size)
            assertEquals(81, guide.locations.sumOf { it.requirements.size })
            assertEquals(718, guide.locations.sumOf { location -> location.requirements.sumOf { it.quantity } })
            assertEquals("tfan0b", guide.sourceSheetId)
            assertEquals(setOf("梭露季", "西拿魔司"), guide.materials.filter { it.itemEntryId == null }.map { it.title }.toSet())
            guide.materials.filter { it.itemEntryId != null }.forEach { material ->
                val entry = requireNotNull(repository.entry(requireNotNull(material.itemEntryId)))
                assertEquals(material.title, entry.title)
                assertEquals(material.imageAsset, entry.imageAsset)
                context.assets.open("wiki/${material.imageAsset}").use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    assertNotNull(bitmap)
                    bitmap?.recycle()
                }
            }
        }
    }

    @Test
    fun restorationGuideIsUnavailableForSchemaEightDatabases() {
        withBundledDatabaseVersion(8) { repository -> assertNull(repository.restorationGuide()) }
    }

    @Test fun theaterProjectsAllPlaysVariantsAndExplicitRewardGaps() {
        WikiDatabaseRepository.open(context).use { repository ->
            val guide = requireNotNull(repository.theaterGuide())
            assertEquals(4, guide.plays.size)
            assertEquals(8, guide.plays.sumOf { it.cues.size })
            assertEquals(12, guide.plays.sumOf { play -> play.rewards.count { it.text != null } })
            assertEquals(4, guide.plays.sumOf { play -> play.rewards.count { it.text == null } })
            assertTrue(guide.preferences.isEmpty())
            assertEquals("unrecorded_in_source", guide.preferenceStatus)
            assertEquals(2, guide.plays.count { it.hasRecordedCoverage })
            val white = guide.plays.single { it.title == "戏曲·斗技场的白发鬼" }
            assertEquals(6, white.cues.single().sourceRow)
            assertEquals("D6", white.cues.single().cellAddress)
            assertEquals(listOf("非常开心地", "满怀喜悦地"), guide.plays.first().cues.last().options)
            assertEquals(guide.activities.first().note, guide.activities.last().note)
            assertEquals(listOf(2, 3), guide.activities.map { it.participants })
            assertEquals("在剧场享受感动的时光", repository.entry(requireNotNull(guide.relatedSidequestId))?.title)
        }
    }

    @Test fun theaterIsUnavailableForSchemaNine() {
        withBundledDatabaseVersion(9) { repository -> assertNull(repository.theaterGuide()) }
    }

    @Test fun fwCharactersFillAffiliationsAndSortRoutesByRenownAndJoinOrder() {
        WikiDatabaseRepository.open(context).use { repository ->
            val allChars = repository.page(WikiPageRequest(categoryId = "characters", limit = 100)).entries
            assertEquals(76, allChars.size)
            val affiliations = repository.options("characters").first { it.key == "affiliation" }.values
            assertTrue("美盖拉的灯火" in affiliations)
            assertFalse(affiliations.any { "\n" in it })
            assertTrue(allChars.count { it.subtitle.startsWith("美盖拉的灯火") } == 5)
            assertTrue(allChars.count { it.subtitle.startsWith("蔷薇风暴") } == 5)

            val kaiChars = repository.page(
                WikiPageRequest(categoryId = "characters", routeKey = "kai", fameDescending = false, limit = 100),
            )
            assertEquals(41, kaiChars.totalMatches)
            assertEquals("凯伊", kaiChars.entries.first().title)
            assertTrue(kaiChars.entries.take(4).all { it.subtitle.startsWith("剧情加入") })

            val kaiDesc = repository.page(
                WikiPageRequest(categoryId = "characters", routeKey = "kai", fameDescending = true, limit = 100),
            )
            assertEquals(41, kaiDesc.totalMatches)
            assertTrue(kaiDesc.entries.first().subtitle.startsWith("名声 Lv.10"))
            assertTrue(kaiDesc.entries.last().subtitle.startsWith("剧情加入"))

            assertEquals(44, repository.page(WikiPageRequest(categoryId = "characters", routeKey = "dit", limit = 100)).totalMatches)
            assertEquals(44, repository.page(WikiPageRequest(categoryId = "characters", routeKey = "seo", limit = 100)).totalMatches)
            assertEquals(42, repository.page(WikiPageRequest(categoryId = "characters", routeKey = "leda", limit = 100)).totalMatches)
            assertEquals(26, repository.page(WikiPageRequest(categoryId = "characters", routeKey = "other", limit = 100)).totalMatches)

            val tiara = allChars.first { it.title == "媞雅拉" }
            val routes = repository.characterRoutes(tiara.id)
            assertEquals(listOf("kai", "dit", "seo", "leda"), routes.map(CharacterRouteSummary::routeKey))
            assertTrue(routes.first { it.routeKey == "dit" }.poachCondition.contains("支付3000G"))
        }
    }

    private fun withBundledDatabaseVersion(version: Int, action: (WikiDatabaseRepository) -> Unit) {
        val oldDatabase = File(temporaryRoot, "schema-$version.db")
        context.assets.open("wiki/content.db").use { input -> oldDatabase.writeBytes(input.readBytes()) }
        SQLiteDatabase.openDatabase(oldDatabase.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.version = version
        }
        val oldBytes = oldDatabase.readBytes()
        val oldHash = MessageDigest.getInstance("SHA-256").digest(oldBytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        val source = WikiAssetSource { name ->
            when (name) {
                "content.db" -> oldBytes.inputStream()
                "content.sha256" -> oldHash.byteInputStream()
                else -> error("Unexpected asset")
            }
        }
        WikiDatabaseRepository.open(context, source).use(action)
    }
}
