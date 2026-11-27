package com.fireemblem.companion.personal

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import com.fireemblem.companion.importer.FefwGrowthSeedSourceArchive
import com.fireemblem.companion.importer.AtomicBuddyArchiveStore
import com.fireemblem.companion.ui.PersonalManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalStoreTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var root: File
    private lateinit var store: PersonalStore
    private lateinit var context: Context
    private lateinit var preferencesName: String

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <Input, Output> onLaunch(requestCode: Int, contract: ActivityResultContract<Input, Output>,
                input: Input, options: ActivityOptionsCompat?) = Unit
        }
    }

    @Before
    fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "personal-test-").toFile()
        preferencesName = "personal-ui-${UUID.randomUUID()}"
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences("$preferencesName-$name", mode)
        }
        store = PersonalStore(context)
    }

    @After fun cleanup() {
        root.deleteRecursively()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        target.deleteSharedPreferences("$preferencesName-personal-view")
        target.deleteSharedPreferences("$preferencesName-sync-credentials")
    }

    @Test
    fun persistsAndMergesWithoutOverwritingConcurrentEditsOrDeletes() {
        val id = UUID.randomUUID().toString()
        val original = store.edit(id, "note", buildJsonObject { put("note", "原文") }, expectedParents = emptySet())
        val branch = original.edit(id, "note", UUID.randomUUID().toString(), buildJsonObject { put("note", "另一设备") })
        store.edit(id, "note", buildJsonObject { put("note", "原文") }, true, original.heads().getValue(id).map { it.revisionId }.toSet())
        store.merge(branch)
        assertEquals(2, store.snapshot().heads().getValue(id).size)
        store.merge(branch)
        assertEquals(3, store.snapshot().revisions.size)
        val beforeFailure = store.snapshot()
        assertThrows(IllegalArgumentException::class.java) { store.edit(id, "note", buildJsonObject { put("note", "错误覆盖") }, expectedParents = emptySet()) }
        assertEquals(beforeFailure, store.snapshot())
        val resolved = store.edit(id, "note", buildJsonObject { put("note", "已解决") }, expectedParents = beforeFailure.heads().getValue(id).map { it.revisionId }.toSet())
        assertEquals(1, resolved.heads().getValue(id).size)
        assertTrue(File(root, "user.db").isFile)
    }

    @Test
    fun malformedImportIsTransactionalAndIndependentFromContentFiles() {
        val sentinel = File(root, "content.db").apply { writeText("unchanged-content") }
        val original = store.edit(UUID.randomUUID().toString(), "profile", buildJsonObject { put("name", "凯伊存档") }, expectedParents = emptySet())
        val bad = original.revisions.single().copy(parents = listOf(UUID.randomUUID().toString()))
        assertThrows(IllegalArgumentException::class.java) { store.merge(PersonalSnapshot(listOf(bad))) }
        assertEquals(original, store.snapshot())
        assertEquals("unchanged-content", sentinel.readText())
    }

    @Test
    fun levelJournalsPersistEditsAndRemainSeparatedByRoute() {
        val ids = com.fireemblem.companion.engine.BuddyCampaignLine.entries.associateWith { UUID.randomUUID().toString() }
        ids.forEach { (line, id) ->
            val journal = com.fireemblem.companion.engine.LevelJournal("测试册", "global", line, "c001",
                listOf(com.fireemblem.companion.engine.LevelObservation(level = 1, profession = "平民", part3 = false)))
            store.edit(id, "level-journal", journal.encode(), expectedParents = emptySet())
        }
        val saved = store.snapshot()
        ids.forEach { (line, id) ->
            val revision = saved.heads().getValue(id).single()
            val journal = com.fireemblem.companion.engine.LevelJournal.decode(revision.payload)
            assertEquals(line, journal.line)
            store.edit(id, "level-journal", journal.copy(observations = emptyList()).encode(), expectedParents = setOf(revision.revisionId))
        }
        assertEquals(4, store.snapshot().heads().size)
        assertTrue(store.snapshot().heads().values.all { com.fireemblem.companion.engine.LevelJournal.decode(it.single().payload).observations.isEmpty() })
    }

    @Test
    fun growthSeedImportStoresOriginalOnceAndKeepsJournalAndSourceAtomic() {
        val original = "{ \"format\": \"fefw-growth-seed\", \"version\": 1, \"records\": [] }\r\n".toByteArray()
        val archive = FefwGrowthSeedSourceArchive.fromBytes(original)
        fun importedJournal(name: String) = com.fireemblem.companion.engine.LevelJournal(
            name = name,
            profile = "global",
            line = com.fireemblem.companion.engine.BuddyCampaignLine.KAI,
            characterId = "c044",
            importSourceSha256 = archive.sourceSha256,
            importSourceRecordIndex = 0,
        )

        val firstId = UUID.randomUUID().toString()
        val first = store.importGrowthSeedJournal(firstId, importedJournal("来源记录一").encode(), archive)
        assertEquals(1, first.revisions.count { it.kind == FefwGrowthSeedSourceArchive.KIND })
        assertArrayEquals(original, store.growthSeedSourceArchive(archive.sourceSha256)!!.restoreBytes())

        val second = store.importGrowthSeedJournal(UUID.randomUUID().toString(), importedJournal("来源记录二").encode(), archive)
        assertEquals(1, second.revisions.count { it.kind == FefwGrowthSeedSourceArchive.KIND })
        assertEquals(2, second.revisions.count { it.kind == com.fireemblem.companion.engine.LevelJournal.KIND })

        val beforeFailure = store.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            store.importGrowthSeedJournal(firstId, importedJournal("不应覆盖").encode(), archive)
        }
        assertEquals(beforeFailure, store.snapshot())
    }

    @Test
    fun syncCredentialsRoundTripWithoutPlaintextPreferences() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-secrets-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = target.getSharedPreferences(name, mode)
        }
        val settings = SecureSyncSettings(isolated)
        try {
            val input = SyncSettings("https://example.invalid/dav/", "owner", "private-webdav-password", "independent-encryption-password")
            settings.save(input)
            assertEquals(input, settings.load())
            val persisted = target.getSharedPreferences(name, Context.MODE_PRIVATE).all.toString()
            assertTrue(!persisted.contains(input.password) && !persisted.contains(input.encryptionPassword))
            settings.clear()
            assertEquals(null, settings.load())
        } finally { target.deleteSharedPreferences(name) }
    }

    @Test
    fun readingPositionPreferenceDefaultsOnAndPersistsWhenDisabled() {
        showPersonalManager()
        compose.onNodeWithText("返回原来的位置").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertEquals(false, context.getSharedPreferences("personal-view", 0).getBoolean("restore-reading-position", true))
        compose.onNodeWithText("返回原来的位置").performClick().assertIsOn()
        assertEquals(true, context.getSharedPreferences("personal-view", 0).getBoolean("restore-reading-position", false))
    }

    @Test
    fun personalUiPrefillsTheSelectedArchiveBeforeEditing() {
        val id = UUID.randomUUID().toString()
        store.edit(id, "profile", buildJsonObject { put("name", "测试存档"); put("route", "leda") }, expectedParents = emptySet())
        context.getSharedPreferences("personal-view", 0).edit().putString("profile", id).commit()
        showPersonalManager()
        compose.waitUntil(15000) { compose.onAllNodesWithText("编辑当前存档").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("编辑当前存档").performScrollTo().performClick()
        compose.onNode(hasText("测试存档") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("存档名称").performTextReplacement("修改后的存档")
        compose.onNodeWithText("保存更改").performClick()

        compose.waitUntil(15000) {
            store.snapshot().heads()[id]?.singleOrNull()?.payload?.get("name")?.toString() == "\"修改后的存档\""
        }
        assertEquals(1, store.snapshot().heads().size)
        assertEquals("\"leda\"", store.snapshot().heads().getValue(id).single().payload["route"].toString())
    }

    @Test
    fun conflictUiRequiresConfirmationAndAllowsCancellation() {
        val id = seedNoteConflict()
        showPersonalManager()
        openConflictChoice()
        assertEquals(2, store.snapshot().heads().getValue(id).size)
        compose.onNodeWithText("取消").performClick()
        assertEquals(2, store.snapshot().heads().getValue(id).size)

        compose.onAllNodesWithText("保留此版本")[0].performScrollTo().performClick()
        compose.onNode(hasText("保留此版本") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15000) { store.snapshot().heads().getValue(id).size == 1 }
        assertEquals(4, store.snapshot().revisions.size)
    }

    @Test
    fun conflictSummariesShowBookmarkStateAndNotesForEachVersion() {
        seedBookmarkConflict()
        showPersonalManager()
        openConflictList()

        compose.onNodeWithText("已收藏", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("未收藏", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已完成", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("未完成", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("手机版本备注").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("平板版本备注").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun syncFailureShowsRetryBesideActionWithoutChangingLocalRecords() {
        val id = UUID.randomUUID().toString()
        val localBefore = store.edit(id, "note", buildJsonObject { put("name", "本机记录"); put("note", "保留此内容") },
            expectedParents = emptySet())
        SecureSyncSettings(context).save(SyncSettings(
            "https://127.0.0.1:1/dav/", "owner", "webdav-secret", "test-encryption-passphrase",
        ))
        showPersonalManager()
        compose.onNodeWithText("备份与同步").performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("已读取保存的同步设置；本次打开后尚未确认同步结果。").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("已保存同步设置 · 手动同步").performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("同步完成", substring = true).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("立即同步").performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("无法连接 WebDAV", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val retryAction = compose.onNodeWithText("重试同步").performScrollTo().assertHeightIsAtLeast(52.dp)
        val errorNotice = compose.onNodeWithText("无法连接 WebDAV", substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(errorNotice.fetchSemanticsNode().boundsInRoot.top >= retryAction.fetchSemanticsNode().boundsInRoot.bottom)
        assertEquals(localBefore, store.snapshot())
    }

    @Test
    fun pendingConflictChoiceCannotResolveAnUnseenRevision() {
        val id = seedNoteConflict()
        val original = PersonalSnapshot(store.snapshot().revisions.filter { it.parents.isEmpty() })
        showPersonalManager()
        openConflictChoice()
        val third = original.edit(id, "note", UUID.randomUUID().toString(), buildJsonObject {
            put("name", "冲突笔记"); put("profileId", "global"); put("note", "第三台设备的新修改")
        })
        store.merge(third)

        compose.onNode(hasText("保留此版本") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("操作未完成，原记录仍保留。", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(3, store.snapshot().heads().getValue(id).size)
        assertEquals(4, store.snapshot().revisions.size)
    }

    @Test
    fun personalUiKeepsBackupActionsReachableWithLargeText() {
        showPersonalManager(fontScale = 1.6f)
        compose.waitUntil(15000) { compose.onAllNodesWithText("备份与同步").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("备份与同步").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("选择备份恢复").performScrollTo().assertHeightIsAtLeast(52.dp)
        compose.onNodeWithText("配置 WebDAV").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("取消").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("我的记录").performScrollTo().performClick()
        compose.onNodeWithText("全部记录").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun personalUiPlacesBackupBesideRecordsWhenSpaceAllows() {
        var availableWidth = 0.dp
        showPersonalManager(width = 800.dp, onWidth = { availableWidth = it })
        compose.waitUntil(15000) { compose.onAllNodesWithText("全部记录").fetchSemanticsNodes().isNotEmpty() }
        if (availableWidth >= 720.dp) {
            val records = compose.onNodeWithText("个人记录 · 0").fetchSemanticsNode().boundsInRoot
            val backup = compose.onNodeWithText("加密备份").fetchSemanticsNode().boundsInRoot
            assertTrue("Expanded backup should be beside records", backup.left > records.right)
        } else {
            compose.onNodeWithText("备份与同步").assertIsDisplayed()
        }
    }

    private fun seedNoteConflict(): String {
        val id = UUID.randomUUID().toString()
        val original = store.edit(id, "note", buildJsonObject {
            put("name", "冲突笔记"); put("profileId", "global"); put("note", "初始内容")
        }, expectedParents = emptySet())
        val branch = original.edit(id, "note", UUID.randomUUID().toString(), buildJsonObject {
            put("name", "冲突笔记"); put("profileId", "global"); put("note", "平板修改")
        })
        store.edit(id, "note", buildJsonObject {
            put("name", "冲突笔记"); put("profileId", "global"); put("note", "手机修改")
        }, expectedParents = original.heads().getValue(id).map(PersonalRevision::revisionId).toSet())
        store.merge(branch)
        return id
    }

    private fun seedBookmarkConflict(): String {
        val id = UUID.randomUUID().toString()
        val original = store.edit(id, "bookmark", buildJsonObject {
            put("profileId", "global"); put("name", "测试资料"); put("favorite", true); put("completed", false); put("note", "手机版本备注")
        }, expectedParents = emptySet())
        val branch = original.edit(id, "bookmark", UUID.randomUUID().toString(), buildJsonObject {
            put("profileId", "global"); put("name", "测试资料"); put("favorite", false); put("completed", true); put("note", "平板版本备注")
        })
        store.edit(id, "bookmark", buildJsonObject {
            put("profileId", "global"); put("name", "测试资料"); put("favorite", true); put("completed", false); put("note", "手机版本备注")
        }, expectedParents = original.heads().getValue(id).map { it.revisionId }.toSet())
        store.merge(branch)
        return id
    }

    private fun showPersonalManager(width: Dp = 360.dp, fontScale: Float = 1f, onWidth: (Dp) -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides registryOwner,
                LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    BoxWithConstraints(Modifier.width(width)) {
                        onWidth(maxWidth)
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            PersonalManager(AtomicBuddyArchiveStore(root.toPath().resolve("buddy")), {}, {})
                        }
                    }
                }
            }
        }
    }

    private fun openConflictChoice() {
        openConflictList()
        compose.onAllNodesWithText("保留此版本")[0].performScrollTo().performClick()
        compose.onNodeWithText("确认保留此版本").assertIsDisplayed()
    }

    private fun openConflictList() {
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("1 条记录待处理 · 查看版本").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("1 条记录待处理 · 查看版本").performScrollTo().performClick()
    }
}
