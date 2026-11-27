package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.personal.PersonalRevision
import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.PersonalTierList
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PersonalTierListScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var profilePreferences: SharedPreferences
    private lateinit var repository: WikiDatabaseRepository

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) = Unit
        }
    }

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "personal-tier-ui-").toFile()
        profilePreferences = target.getSharedPreferences("tier-ui-${UUID.randomUUID()}", Context.MODE_PRIVATE)
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

    @Test fun assigningCharacterToTierPersistsAndShowsOnBoard() {
        val character = firstCharacter()
        showScreen()

        compose.onNodeWithTag("tier-mode-pick").performScrollTo().performClick()
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("tier-pick:${character.id}").fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithTag("tier-pick:${character.id}").performScrollTo().performClick()
        compose.onNodeWithTag("tier-dialog-save").performClick()

        compose.waitUntil(5000) {
            val saved = PersonalTierList.readProfile(PersonalStore(context).snapshot(), "profile-a")
            saved.lists.firstOrNull()?.list?.entryFor(character.id) != null
        }

        compose.onNodeWithTag("tier-mode-board").performScrollTo().performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("tier-entry:${character.id}"))
        compose.onNodeWithTag("tier-entry:${character.id}").assertIsDisplayed()
    }

    @Test fun creatingSecondTierListSwitchesActiveList() {
        showScreen()
        compose.onNodeWithTag("tier-new-list").performScrollTo().performClick()
        compose.onNodeWithTag("tier-meta-name").performTextInput("二周目梯度榜")
        compose.onNodeWithTag("tier-meta-save").performClick()

        compose.waitUntil(5000) {
            val lists = PersonalTierList.readProfile(PersonalStore(context).snapshot(), "profile-a").lists
            lists.any { it.list.name == "二周目梯度榜" }
        }
        compose.onNodeWithText("二周目梯度榜（0）").assertIsDisplayed()
    }

    @Test fun conflictRevisionsAreShownReadOnlyWithoutOverwriting() {
        val entityId = PersonalTierList.defaultEntityId("profile-a")
        val listOne = PersonalTierList(profileId = "profile-a", name = "版本一")
        val listTwo = PersonalTierList(profileId = "profile-a", name = "版本二")
        val store = PersonalStore(context)
        store.edit(entityId, PersonalTierList.KIND, listOne.payload(), expectedParents = emptySet())
        store.merge(
            PersonalSnapshot(
                listOf(
                    PersonalRevision(
                        revisionId = UUID.randomUUID().toString(),
                        entityId = entityId,
                        kind = PersonalTierList.KIND,
                        deviceId = UUID.randomUUID().toString(),
                        parents = emptyList(),
                        deleted = false,
                        modifiedAt = System.currentTimeMillis() + 1000L,
                        payload = listTwo.payload(),
                    ),
                ),
            ),
        )

        showScreen(initialEntityId = entityId)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("冲突", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(2, store.snapshot().heads().getValue(entityId).size)
        assertTrue(PersonalTierList.readProfile(store.snapshot(), "profile-a").conflictEntityIds.contains(entityId))
        compose.onNodeWithText("冲突", substring = true).assertIsDisplayed()
    }

    private fun firstCharacter(): WikiEntry =
        repository.page(WikiPageRequest(categoryId = "characters", limit = 1)).entries.first()

    private fun showScreen(initialEntityId: String? = null) {
        compose.setContent {
            MaterialTheme {
                ReadingPositionProvider {
                    CompositionLocalProvider(
                        LocalContext provides context,
                        LocalActivityResultRegistryOwner provides registryOwner,
                    ) {
                        PersonalTierListScreen(
                            repository = repository,
                            onBack = {},
                            onOpenWiki = {},
                            initialEntityId = initialEntityId,
                        )
                    }
                }
            }
        }
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("tier-mode-board").fetchSemanticsNode() }.isSuccess
        }
    }
}
