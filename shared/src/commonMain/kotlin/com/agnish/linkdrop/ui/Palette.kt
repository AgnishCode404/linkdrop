package com.agnish.linkdrop.ui

/**
 * The colors of both apps (always dark), as 0xAARRGGBB. Each app builds its Compose theme from
 * these; `shared` itself has no UI. PaletteTest checks that every text color used on a background
 * has a contrast ratio of at least 4.5:1 there.
 */
object Palette {
    /** The page behind everything. */
    const val BACKGROUND = 0xFF0E1116

    /** Cards, slightly lighter than the page. */
    const val SURFACE = 0xFF171B22

    /** Dialogs and cards that stand out (an offer waiting for an answer). */
    const val SURFACE_RAISED = 0xFF1F2530

    /** Borders and dividers (decorative: never the only way to see something). */
    const val OUTLINE = 0xFF3A4250

    /** The empty part of a progress bar. */
    const val TRACK = 0xFF2A303B

    const val TEXT = 0xFFE6EAF0

    /** Less important text: addresses, sizes, fingerprints, declined and cancelled files. */
    const val TEXT_MUTED = 0xFFA3ACB9

    /** The one accent: main actions, progress, "connected", the pairing code. */
    const val ACCENT = 0xFF5B9BFF

    /** Finished files. */
    const val SUCCESS = 0xFF3FB950

    /** Decline, Cancel, Forget, Disconnect and errors. */
    const val DANGER = 0xFFFF6B6B

    /** Warnings: the plaintext development build, files that can be retried. */
    const val WARNING = 0xFFE3A72F

    /** Text on ACCENT, SUCCESS or DANGER fills (white would be too faint on them). */
    const val ON_FILL = 0xFF0E1116
}
