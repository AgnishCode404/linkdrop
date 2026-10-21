package com.agnish.linkdrop.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WCAG 2 contrast: every text color must reach 4.5:1 on each background it is used on. */
class PaletteTest {

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun contrastMatchesKnownValues() {
        assertEquals(21.0, contrast(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, contrast(0xFF777777, 0xFF777777), 0.01)
    }

    @Test
    fun textColorsAreReadableOnEveryBackground() {
        val backgrounds = mapOf(
            "BACKGROUND" to Palette.BACKGROUND,
            "SURFACE" to Palette.SURFACE,
            "SURFACE_RAISED" to Palette.SURFACE_RAISED,
        )
        val texts = mapOf(
            "TEXT" to Palette.TEXT,
            "TEXT_MUTED" to Palette.TEXT_MUTED,
            "ACCENT" to Palette.ACCENT,
            "SUCCESS" to Palette.SUCCESS,
            "DANGER" to Palette.DANGER,
            "WARNING" to Palette.WARNING,
        )
        for ((textName, text) in texts) for ((backgroundName, background) in backgrounds) {
            val ratio = contrast(text, background)
            assertTrue(ratio >= 4.5, "$textName on $backgroundName: $ratio")
        }
    }

    @Test
    fun textOnFilledButtonsIsReadable() {
        for (fill in listOf(Palette.ACCENT, Palette.SUCCESS, Palette.DANGER)) {
            val ratio = contrast(Palette.ON_FILL, fill)
            assertTrue(ratio >= 4.5, "ON_FILL on ${fill.toString(16)}: $ratio")
        }
    }

    @Test
    fun progressIsVisibleOnItsTrack() {
        // Non-text graphics need 3:1 (WCAG 1.4.11).
        assertTrue(contrast(Palette.ACCENT, Palette.TRACK) >= 3.0)
    }
}
