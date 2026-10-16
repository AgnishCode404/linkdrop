package com.agnish.linkdrop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileNamesTest {

    @Test
    fun ordinaryNamesAreKept() {
        for (name in listOf("photo.jpg", "My Report (final).pdf", "архив.zip", "日本語.txt", "emoji 😀.png", "no-extension")) {
            assertEquals(name, FileNames.clean(name))
        }
    }

    @Test
    fun onlyTheLastPathPartIsKept() {
        assertEquals("passwd", FileNames.clean("../../etc/passwd"))
        assertEquals("evil.sh", FileNames.clean("/home/user/.bashrc/evil.sh"))
        assertEquals("win.txt", FileNames.clean("C:\\Windows\\win.txt"))
        assertEquals("x.txt", FileNames.clean("..\\..\\x.txt"))
    }

    @Test
    fun unusableNamesAreRejected() {
        for (name in listOf("", " ", ".", "..", "...", "a/", "a/..", "dir\\", "  .  ")) {
            assertNull(FileNames.clean(name), "should reject \"$name\"")
        }
    }

    @Test
    fun controlAndInvisibleFormattingCharactersAreRejected() {
        assertNull(FileNames.clean("bad\nname.txt"))
        assertNull(FileNames.clean("nul\u0000.txt"))
        assertNull(FileNames.clean("tab\t.txt"))
        assertNull(FileNames.clean("del\u007f.txt"))
        // Right-to-left override: "invoice\u202Egpj.exe" displays as "invoiceexe.jpg".
        assertNull(FileNames.clean("invoice\u202Egpj.exe"))
        assertNull(FileNames.clean("zero\u200Bwidth.txt"))
    }

    @Test
    fun leadingDotsAreRemovedSoFilesAreNotHidden() {
        assertEquals("bashrc", FileNames.clean(".bashrc"))
        assertEquals("config.json", FileNames.clean("...config.json"))
    }

    @Test
    fun reservedCharactersAreReplaced() {
        assertEquals("a_b_c_d_e_f_g.txt", FileNames.clean("a\"b*c:d<e>f?g.txt"))
        assertEquals("pipe_.txt", FileNames.clean("pipe|.txt"))
    }

    @Test
    fun surroundingSpacesAreTrimmed() {
        assertEquals("spaced.txt", FileNames.clean("  spaced.txt  "))
    }

    @Test
    fun longNamesAreShortenedKeepingTheExtension() {
        val cleaned = FileNames.clean("a".repeat(300) + ".jpg")!!
        assertEquals(TransferLimits.MAX_NAME_BYTES, cleaned.encodeToByteArray().size)
        assertTrue(cleaned.endsWith(".jpg"))
    }

    @Test
    fun shorteningNeverSplitsMultiByteCharacters() {
        // "日" is 3 bytes and "😀" is 4 bytes (a surrogate pair) in UTF-8.
        for (name in listOf("日".repeat(200) + ".txt", "😀".repeat(100) + ".txt")) {
            val cleaned = FileNames.clean(name)!!
            assertTrue(cleaned.encodeToByteArray().size <= TransferLimits.MAX_NAME_BYTES, cleaned)
            assertTrue(cleaned.endsWith(".txt"))
            // Re-encoding is lossless only if no character was cut in half.
            assertEquals(cleaned, cleaned.encodeToByteArray().decodeToString())
        }
    }

    @Test
    fun hugeExtensionIsNotKept() {
        val cleaned = FileNames.clean("a." + "b".repeat(300))!!
        assertTrue(cleaned.encodeToByteArray().size <= TransferLimits.MAX_NAME_BYTES)
    }

    @Test
    fun uniqueAddsANumberBeforeTheExtension() {
        val taken = mutableSetOf("photo.jpg", "photo (1).jpg")
        assertEquals("photo (2).jpg", FileNames.unique("photo.jpg") { it in taken })
        assertEquals("new.jpg", FileNames.unique("new.jpg") { it in taken })
        assertEquals("README (1)", FileNames.unique("README") { it == "README" })
        assertEquals("archive.tar (1).gz", FileNames.unique("archive.tar.gz") { it == "archive.tar.gz" })
    }

    @Test
    fun uniqueGivesUpAfterMaxAttempts() {
        assertNull(FileNames.unique("x.txt", maxAttempts = 3) { true })
    }

    @Test
    fun uniqueKeepsLongNamesWithinTheLimit() {
        val long = FileNames.clean("a".repeat(300) + ".jpg")!!
        val next = FileNames.unique(long) { it == long }!!
        assertTrue(next.encodeToByteArray().size <= TransferLimits.MAX_NAME_BYTES)
        assertTrue(next.endsWith(" (1).jpg"), next)
    }
}
