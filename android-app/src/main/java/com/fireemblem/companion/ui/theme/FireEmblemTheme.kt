package com.fireemblem.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.fireemblem.companion.ui.AppAppearanceMode
import com.fireemblem.companion.ui.rememberAppearanceSettings

private val ClaudeLightColors = lightColorScheme(
    primary = Color(0xFFB85A3A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6E3DA),
    onPrimaryContainer = Color(0xFF5C2816),
    secondary = Color(0xFF3A6690),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2ECF5),
    onSecondaryContainer = Color(0xFF1E3E5C),
    tertiary = Color(0xFF4D6037),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4ECE0),
    onTertiaryContainer = Color(0xFF2B381D),
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF141413),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFE8E6DC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F1E8),
    surfaceContainer = Color(0xFFE8E6DC),
    surfaceContainerHigh = Color(0xFFE2DFD4),
    surfaceContainerHighest = Color(0xFFDAD6CA),
    onSurfaceVariant = Color(0xFF5F5D56),
    outline = Color(0xFFD8D5CA),
    outlineVariant = Color(0xFFE8E6DC),
    surfaceTint = Color(0xFFB85A3A),
    error = Color(0xFF9D3029),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE6E4),
    onErrorContainer = Color(0xFF6B1E18),
)

private val ClaudeDarkColors = darkColorScheme(
    primary = Color(0xFFD97757),
    onPrimary = Color(0xFF141413),
    primaryContainer = Color(0xFF4A281C),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFF6A9BCC),
    onSecondary = Color(0xFF141413),
    secondaryContainer = Color(0xFF243B50),
    onSecondaryContainer = Color(0xFFD7E8F8),
    tertiary = Color(0xFF8FA672),
    onTertiary = Color(0xFF141413),
    tertiaryContainer = Color(0xFF2D3A21),
    onTertiaryContainer = Color(0xFFDDEBD0),
    background = Color(0xFF141413),
    onBackground = Color(0xFFFAF9F5),
    surface = Color(0xFF1E1E1C),
    onSurface = Color(0xFFFAF9F5),
    surfaceVariant = Color(0xFF2E2D2A),
    surfaceContainerLowest = Color(0xFF111110),
    surfaceContainerLow = Color(0xFF252522),
    surfaceContainer = Color(0xFF2E2D2A),
    surfaceContainerHigh = Color(0xFF363531),
    surfaceContainerHighest = Color(0xFF3F3E39),
    onSurfaceVariant = Color(0xFFB0AEA5),
    outline = Color(0xFF44433E),
    outlineVariant = Color(0xFF32312D),
    surfaceTint = Color(0xFFD97757),
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF57120E),
    errorContainer = Color(0xFF642420),
    onErrorContainer = Color(0xFFFFDAD5),
)

internal fun TextStyle.tabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
fun FireEmblemTheme(
    appearanceOverride: AppAppearanceMode? = null,
    content: @Composable () -> Unit,
) {
    val mode = appearanceOverride ?: rememberAppearanceSettings().mode
    val useDark = when (mode) {
        AppAppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppAppearanceMode.LIGHT -> false
        AppAppearanceMode.DARK -> true
    }
    val base = MaterialTheme.typography
    val typography = base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
    )
    MaterialTheme(
        colorScheme = if (useDark) ClaudeDarkColors else ClaudeLightColors,
        typography = typography,
        content = content,
    )
}
