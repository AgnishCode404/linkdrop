package com.agnish.linkdrop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.agnish.linkdrop.ui.Palette

/** Palette colors for roles Material's color scheme has no slot for. */
object LinkDropColors {
    val Success = Color(Palette.SUCCESS)
    val Warning = Color(Palette.WARNING)

    /** The empty part of a progress bar. */
    val Track = Color(Palette.TRACK)
}

/** The shared palette (see Palette in shared) mapped onto Material 3's roles. Always dark. */
private val DarkColors: ColorScheme = run {
    val background = Color(Palette.BACKGROUND)
    val surface = Color(Palette.SURFACE)
    val raised = Color(Palette.SURFACE_RAISED)
    val text = Color(Palette.TEXT)
    val accent = Color(Palette.ACCENT)
    val onFill = Color(Palette.ON_FILL)
    val outline = Color(Palette.OUTLINE)
    darkColorScheme(
        primary = accent,
        onPrimary = onFill,
        primaryContainer = raised,
        onPrimaryContainer = text,
        secondary = accent,
        onSecondary = onFill,
        secondaryContainer = raised,
        onSecondaryContainer = text,
        tertiary = Color(Palette.SUCCESS),
        onTertiary = onFill,
        tertiaryContainer = raised,
        onTertiaryContainer = text,
        background = background,
        onBackground = text,
        // Cards (OutlinedCard, Card) take "surface"; pages set background themselves.
        surface = surface,
        onSurface = text,
        surfaceVariant = raised,
        onSurfaceVariant = Color(Palette.TEXT_MUTED),
        surfaceTint = surface, // no blue tint on elevated surfaces
        surfaceBright = raised,
        surfaceDim = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = raised,
        outline = outline,
        outlineVariant = outline,
        error = Color(Palette.DANGER),
        onError = onFill,
        errorContainer = raised,
        onErrorContainer = Color(Palette.DANGER),
        inverseSurface = text,
        inverseOnSurface = background,
        scrim = Color.Black,
    )
}

/** The app's theme: dark always, whatever the system is set to. */
@Composable
fun LinkDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
