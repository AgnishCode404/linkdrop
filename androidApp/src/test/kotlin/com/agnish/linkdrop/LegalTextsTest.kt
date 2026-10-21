package com.agnish.linkdrop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The About screen's texts, from the same files the build puts into the APK. */
class LegalTextsTest {
    private val texts = LegalTexts(
        license = File("../LICENSE").readText().trim(),
        notices = File("../THIRD-PARTY-NOTICES").readText(),
    )

    @Test
    fun licenseIsLinkDropsMitLicense() {
        assertTrue(texts.license.startsWith("MIT License"), texts.license.take(40))
        assertTrue("Copyright (c) 2026 Agnish" in texts.license)
    }

    @Test
    fun noticesSplitIntoBlocksWithoutLosingText() {
        val blocks = texts.noticeBlocks
        assertTrue(blocks.size > 10, "${blocks.size} blocks")
        assertTrue(blocks.none { it.isBlank() })
        // Nothing is lost: the blocks hold every non-blank line of the file.
        val lines = texts.notices.lines().filter { it.isNotBlank() }.map { it.trimEnd() }
        assertEquals(lines, blocks.flatMap { it.lines() }.filter { it.isNotBlank() })
        assertTrue(blocks.any { "com.squareup.okhttp3:okhttp-android" in it })
    }
}
