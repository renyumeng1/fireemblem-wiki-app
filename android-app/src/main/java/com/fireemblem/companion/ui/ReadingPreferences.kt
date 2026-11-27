package com.fireemblem.companion.ui

import android.content.SharedPreferences
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

internal const val RESTORE_READING_POSITION = "restore-reading-position"

internal data class ReadingSettings(val restorePosition: Boolean, val setRestorePosition: (Boolean) -> Unit)

@Composable
internal fun rememberReadingSettings(): ReadingSettings {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("personal-view", 0) }
    var enabled by remember(preferences) { mutableStateOf(preferences.getBoolean(RESTORE_READING_POSITION, true)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == RESTORE_READING_POSITION) enabled = preferences.getBoolean(RESTORE_READING_POSITION, true)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return ReadingSettings(enabled) { preferences.edit().putBoolean(RESTORE_READING_POSITION, it).apply() }
}

internal class ReadingPositions {
    private val positions = linkedMapOf<String, List<Int>>()

    fun get(key: String): List<Int> = positions[key] ?: listOf(0, 0)

    fun put(key: String, index: Int, offset: Int) {
        positions.remove(key)
        positions[key] = listOf(index, offset)
        while (positions.size > 128) positions.remove(positions.keys.first())
    }

    companion object {
        val Saver = mapSaver(
            save = { value: ReadingPositions -> value.positions.mapValues { ArrayList(it.value) } },
            restore = { saved -> ReadingPositions().apply {
                saved.forEach { (key, value) ->
                    val position = value as List<*>
                    put(key, position[0] as Int, position[1] as Int)
                }
            } },
        )
    }
}

private val LocalReadingPositions = staticCompositionLocalOf<ReadingPositions?> { null }

@Composable
internal fun ReadingPositionProvider(content: @Composable () -> Unit) {
    val positions = rememberSaveable(saver = ReadingPositions.Saver) { ReadingPositions() }
    CompositionLocalProvider(LocalReadingPositions provides positions, content = content)
}

@Composable
private fun readingPositions(): ReadingPositions = LocalReadingPositions.current
    ?: rememberSaveable(saver = ReadingPositions.Saver) { ReadingPositions() }

@Composable
internal fun rememberReadingListState(key: String, ready: Boolean = true): LazyListState {
    val positions = readingPositions()
    val enabled by rememberUpdatedState(rememberReadingSettings().restorePosition)
    val state = remember(key, ready) {
        val position = if (ready && enabled) positions.get(key) else listOf(0, 0)
        LazyListState(position[0], position[1])
    }
    LaunchedEffect(state, ready) {
        if (ready) {
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> positions.put(key, index, offset) }
        }
    }
    DisposableEffect(state, key, ready) {
        onDispose {
            if (ready) {
                positions.put(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            }
        }
    }
    return state
}

@Composable
internal fun rememberReadingGridState(key: String, ready: Boolean = true): LazyGridState {
    val positions = readingPositions()
    val enabled by rememberUpdatedState(rememberReadingSettings().restorePosition)
    val state = remember(key, ready) {
        val position = if (ready && enabled) positions.get(key) else listOf(0, 0)
        LazyGridState(position[0], position[1])
    }
    LaunchedEffect(state, ready) {
        if (ready) {
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> positions.put(key, index, offset) }
        }
    }
    DisposableEffect(state, key, ready) {
        onDispose {
            if (ready) {
                positions.put(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            }
        }
    }
    return state
}

@Composable
internal fun rememberReadingScrollState(key: String, ready: Boolean = true): ScrollState {
    val positions = readingPositions()
    val enabled = rememberReadingSettings().restorePosition
    val target = remember(key) { if (enabled) positions.get(key)[1] else 0 }
    val state = remember(key) { ScrollState(0) }
    val lastRecordedOffset = remember(key) { mutableIntStateOf(target) }
    LaunchedEffect(state, ready) {
        if (!ready) return@LaunchedEffect
        var restored = target == 0
        snapshotFlow { Triple(state.maxValue, state.value, state.isScrollInProgress) }
            .collect { (maximum, offset, scrolling) ->
                if (!restored && scrolling) restored = true
                if (!restored && maximum != Int.MAX_VALUE) {
                    state.scrollTo(target.coerceAtMost(maximum))
                    restored = maximum >= target
                    if (restored) lastRecordedOffset.intValue = state.value
                } else if (restored) {
                    lastRecordedOffset.intValue = offset
                    positions.put(key, 0, offset)
                }
            }
    }
    DisposableEffect(state, key, ready) {
        onDispose {
            if (ready) {
                val offset = if (state.maxValue < lastRecordedOffset.intValue) {
                    lastRecordedOffset.intValue
                } else {
                    state.value
                }
                positions.put(key, 0, offset)
            }
        }
    }
    return state
}
