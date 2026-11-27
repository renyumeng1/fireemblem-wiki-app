package com.fireemblem.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.engine.BuddyGrowthInput
import com.fireemblem.companion.engine.BuddyRules
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SeedForecastScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rePredictingWithSameInputsRunsCalculationAgain() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val calculations = AtomicInteger()

        compose.setContent {
            CompositionLocalProvider(LocalContext provides target) {
                MaterialTheme {
                    SeedForecastScreen(
                        onBack = {},
                        forecastCalculator = { rules: BuddyRules, seed: Long, input: BuddyGrowthInput ->
                            if (calculations.incrementAndGet() == 2) throw IllegalArgumentException("相同输入已重新计算")
                            withContext(Dispatchers.Default) { rules.forecast(seed, input) }
                        },
                    )
                }
            }
        }

        compose.waitUntil(15000) {
            compose.onAllNodesWithText("升级前种子").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("升级前种子"))
        compose.onNodeWithText("升级前种子").performScrollTo().performTextInput("42")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("预测后续升级"))
        compose.onNodeWithText("预测后续升级").performClick()
        compose.waitUntil(15000) {
            calculations.get() == 1 && compose.onAllNodesWithText("重新预测").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("重新预测").performScrollTo().performClick()
        compose.waitUntil(15000) {
            calculations.get() == 2 && compose.onAllNodesWithText("相同输入已重新计算").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("相同输入已重新计算").performScrollTo().assertIsDisplayed()
        assertEquals(2, calculations.get())
    }
}
