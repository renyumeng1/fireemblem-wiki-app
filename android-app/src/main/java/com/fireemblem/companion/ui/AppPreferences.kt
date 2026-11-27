package com.fireemblem.companion.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fireemblem.companion.content.CompanionGame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val APPEARANCE_MODE_KEY = "appearance-mode"
internal const val ACTIVE_GAME_KEY = "active-game"
private const val RECENT_WIKI_KEY = "recent-wiki-history"

enum class AppAppearanceMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "暖白浅色"),
    DARK("dark", "炭黑深色"),
    ;

    companion object {
        fun fromId(id: String?): AppAppearanceMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

internal data class AppearanceSettings(
    val mode: AppAppearanceMode,
    val setMode: (AppAppearanceMode) -> Unit,
)

@Composable
internal fun rememberAppearanceSettings(): AppearanceSettings {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("personal-view", 0) }
    var mode by remember(preferences) {
        mutableStateOf(AppAppearanceMode.fromId(preferences.getString(APPEARANCE_MODE_KEY, AppAppearanceMode.SYSTEM.id)))
    }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == APPEARANCE_MODE_KEY) {
                mode = AppAppearanceMode.fromId(preferences.getString(APPEARANCE_MODE_KEY, AppAppearanceMode.SYSTEM.id))
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return AppearanceSettings(mode) { next ->
        preferences.edit().putString(APPEARANCE_MODE_KEY, next.id).apply()
    }
}

internal data class ActiveGameSettings(
    val game: CompanionGame,
    val setGame: (CompanionGame) -> Unit,
)

@Composable
internal fun rememberActiveGameSettings(): ActiveGameSettings {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("personal-view", 0) }
    var game by remember(preferences) {
        mutableStateOf(CompanionGame.fromId(preferences.getString(ACTIVE_GAME_KEY, CompanionGame.FORTUNES_WEAVE.id)))
    }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ACTIVE_GAME_KEY) {
                game = CompanionGame.fromId(preferences.getString(ACTIVE_GAME_KEY, CompanionGame.FORTUNES_WEAVE.id))
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return ActiveGameSettings(game) { next ->
        preferences.edit().putString(ACTIVE_GAME_KEY, next.id).apply()
    }
}

internal data class RecentWikiItem(
    val gameId: String,
    val entryId: String,
    val title: String,
    val categoryLabel: String,
)

internal object RecentWikiHistory {
    private const val MAX_ITEMS = 12

    fun list(context: Context, gameId: String): List<RecentWikiItem> =
        readAll(context).filter { it.gameId == gameId }

    fun record(context: Context, item: RecentWikiItem) {
        val updated = (listOf(item) + readAll(context).filterNot {
            it.gameId == item.gameId && it.entryId == item.entryId
        }).take(MAX_ITEMS)
        writeAll(context, updated)
    }

    fun clear(context: Context, gameId: String) {
        val remaining = readAll(context).filterNot { it.gameId == gameId }
        writeAll(context, remaining)
    }

    private fun readAll(context: Context): List<RecentWikiItem> {
        val raw = context.getSharedPreferences("personal-view", 0).getString(RECENT_WIKI_KEY, null)
            ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val gameId = obj["gameId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val entryId = obj["entryId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val categoryLabel = obj["categoryLabel"]?.jsonPrimitive?.content ?: ""
                RecentWikiItem(gameId, entryId, title, categoryLabel)
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(context: Context, items: List<RecentWikiItem>) {
        val array = JsonArray(items.map { item ->
            buildJsonObject {
                put("gameId", item.gameId)
                put("entryId", item.entryId)
                put("title", item.title)
                put("categoryLabel", item.categoryLabel)
            }
        })
        context.getSharedPreferences("personal-view", 0)
            .edit()
            .putString(RECENT_WIKI_KEY, array.toString())
            .apply()
    }
}
