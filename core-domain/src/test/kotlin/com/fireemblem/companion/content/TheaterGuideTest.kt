package com.fireemblem.companion.content

import com.fireemblem.companion.personal.EncryptedBackup
import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.TheaterBookmark
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TheaterGuideTest {
    private val complete = TheaterPlay("theater-play:first", "彩虹", (2..4).map {
        TheaterCue(it - 2, it, "B$it", "开心地\n满怀喜悦地", listOf("开心地", "满怀喜悦地"))
    }, TheaterRating.entries.mapIndexed { index, rating -> TheaterReward(rating, index + 7, "B${index + 7}", "1000酬金") })
    private val partial = TheaterPlay("theater-play:second", "白发鬼", listOf(TheaterCue(4, 6, "C6", "愤恨一般地", listOf("愤恨一般地"))),
        TheaterRating.entries.mapIndexed { index, rating -> TheaterReward(rating, index + 7, "C${index + 7}", null) })
    private val preferences = listOf(TheaterCharacterPreference("characters:first", complete.id, "擅长", "来源", "test", 1))
    private val guide = TheaterGuideData(listOf(complete, partial), emptyList(), emptyList(), preferences, "recorded",
        "", null, "", "test", "测试", "9", "", "weekly", "活动")
    private val device = "11111111-1111-1111-1111-111111111111"

    @Test fun searchesVariantsAndRewardsWithoutTreatingGapsAsZero() {
        assertEquals(listOf(complete), TheaterGuideQuery.plays(guide, " 喜悦 "))
        assertEquals(listOf(complete), TheaterGuideQuery.plays(guide, "1000"))
        assertEquals(listOf(partial), TheaterGuideQuery.plays(guide, coverage = TheaterCoverage.GAPS))
        assertEquals(listOf(complete), TheaterGuideQuery.plays(guide, coverage = TheaterCoverage.RECORDED))
        assertTrue(TheaterGuideQuery.plays(guide, favorites = emptySet()).isEmpty())
        assertEquals(listOf(partial), TheaterGuideQuery.plays(guide, favorites = setOf(partial.id)))
        assertEquals(6, partial.cues.single().sourceRow)
        assertTrue(partial.rewards.all { it.text == null })
    }

    @Test fun preferenceDirectionsAgreeAndMissingDoesNotBecomeDislike() {
        assertEquals(preferences, TheaterGuideQuery.preferences(guide, characterId = "characters:first"))
        assertEquals(preferences, TheaterGuideQuery.preferences(guide, playId = complete.id))
        assertTrue(TheaterGuideQuery.preferences(guide, characterId = "characters:unknown").isEmpty())
    }

    @Test fun favoritesPersistAcrossBackupAndStayIsolatedByProfileAndPlay() {
        val bookmark = TheaterBookmark("first", complete.id, complete.title, true)
        val entity = TheaterBookmark.entityId("first", complete.id)
        assertNotEquals(entity, TheaterBookmark.entityId("second", complete.id))
        assertNotEquals(entity, TheaterBookmark.entityId("first", partial.id))
        val snapshot = PersonalSnapshot(emptyList()).edit(entity, "bookmark", device, bookmark.payload())
        val password = "theater-backup-test".toCharArray()
        val restored = EncryptedBackup.decrypt(EncryptedBackup.encrypt(snapshot, password), password)
        password.fill('\u0000')
        assertEquals(bookmark, TheaterBookmark.read(restored, "first", complete.id, complete.title))
        assertFalse(TheaterBookmark.read(restored, "second", complete.id, complete.title).favorite)
        val removed = restored.edit(entity, "bookmark", device, bookmark.copy(favorite = false).payload())
        assertFalse(TheaterBookmark.read(removed, "first", complete.id, complete.title).favorite)
        val deleted = restored.edit(entity, "bookmark", device, bookmark.payload(), deleted = true)
        assertFalse(TheaterBookmark.read(deleted, "first", complete.id, complete.title).favorite)
    }

    @Test fun conflictsAndStaleWritesCannotSilentlyReplaceBookmarks() {
        val bookmark = TheaterBookmark("first", complete.id, complete.title, true)
        val base = PersonalSnapshot(emptyList())
        val entity = TheaterBookmark.entityId("first", complete.id)
        val left = base.edit(entity, "bookmark", device, bookmark.payload())
        val right = base.edit(entity, "bookmark", device, bookmark.copy(favorite = false).payload())
        assertThrows(IllegalArgumentException::class.java) { TheaterBookmark.read(left.merge(right), "first", complete.id, complete.title) }
        assertThrows(IllegalArgumentException::class.java) { left.edit(entity, "bookmark", device, bookmark.payload(), expectedParents = emptySet()) }
    }

    @Test fun malformedAndCrossProfilePayloadsAreNotEmptyWritableFavorites() {
        val bookmark = TheaterBookmark("first", complete.id, complete.title)
        val entity = TheaterBookmark.entityId("first", complete.id)
        val base = PersonalSnapshot(emptyList())
        val invalid = listOf(
            buildJsonObject { bookmark.payload().forEach { (key, value) -> put(key, value) }; put("version", 2) },
            buildJsonObject { bookmark.payload().forEach { (key, value) -> put(key, value) }; put("favorite", "true") },
            bookmark.copy(profileId = "other").payload(),
            bookmark.copy(playId = partial.id).payload(),
        )
        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                TheaterBookmark.read(base.edit(entity, "bookmark", device, payload), "first", complete.id, complete.title)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TheaterBookmark.read(base.edit(entity, "progress", device, bookmark.payload()), "first", complete.id, complete.title)
        }
    }
}
