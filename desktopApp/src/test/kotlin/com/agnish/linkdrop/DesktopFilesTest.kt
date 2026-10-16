package com.agnish.linkdrop

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopFilesTest {

    private val root: Path = createTempDirectory("linkdrop-test")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun names(dir: Path) = dir.listDirectoryEntries().map { it.name }.sorted()

    @Test
    fun committedFileAppearsUnderItsNameWithNoPartFileLeft() = runBlocking {
        val sink = FolderSink(root.resolve("LinkDrop"))
        val writer = sink.create("photo.jpg", 3, "image/jpeg")
        writer.write(byteArrayOf(1, 2))
        writer.write(byteArrayOf(3))
        // Not visible under its real name before commit.
        assertTrue(names(root.resolve("LinkDrop")).none { it == "photo.jpg" })

        assertEquals("photo.jpg", writer.commit())
        assertEquals(listOf("photo.jpg"), names(root.resolve("LinkDrop")))
        assertContentEquals(byteArrayOf(1, 2, 3), root.resolve("LinkDrop/photo.jpg").readBytes())
    }

    @Test
    fun abortedFileLeavesNothing() = runBlocking {
        val sink = FolderSink(root)
        val writer = sink.create("photo.jpg", 3, null)
        writer.write(byteArrayOf(1, 2))
        writer.abort()
        assertTrue(names(root).isEmpty())
    }

    @Test
    fun existingFilesAreNeverOverwritten() = runBlocking {
        root.resolve("photo.jpg").writeBytes(byteArrayOf(9))
        val sink = FolderSink(root)
        val first = sink.create("photo.jpg", 1, null).apply { write(byteArrayOf(1)) }
        val second = sink.create("photo.jpg", 1, null).apply { write(byteArrayOf(2)) }

        assertEquals("photo (1).jpg", first.commit())
        assertEquals("photo (2).jpg", second.commit())
        assertContentEquals(byteArrayOf(9), root.resolve("photo.jpg").readBytes())
        assertContentEquals(byteArrayOf(1), root.resolve("photo (1).jpg").readBytes())
        assertContentEquals(byteArrayOf(2), root.resolve("photo (2).jpg").readBytes())
    }

    @Test
    fun symlinkWithTheSameNameIsNotWrittenThrough() = runBlocking {
        val elsewhere = Files.createDirectory(root.resolve("elsewhere")).resolve("target.txt")
        val dir = Files.createDirectory(root.resolve("in"))
        Files.createSymbolicLink(dir.resolve("note.txt"), elsewhere) // dangling link
        val writer = FolderSink(dir).create("note.txt", 1, null).apply { write(byteArrayOf(1)) }

        assertEquals("note (1).txt", writer.commit())
        assertTrue(Files.notExists(elsewhere))
    }

    @Test
    fun leftoverPartFilesAreDeletedOtherFilesKept() {
        root.resolve(".linkdrop-1234.part").writeText("partial")
        root.resolve("keep.part").writeText("not ours")
        root.resolve("photo.jpg").writeText("x")
        FolderSink(root).deleteLeftovers()
        assertEquals(listOf("keep.part", "photo.jpg"), names(root))
    }

    @Test
    fun freeSpaceIsKnown() {
        assertNotNull(FolderSink(root.resolve("new")).freeBytes())
    }

    @Test
    fun pathSourceReadsTheWholeFile() = runBlocking {
        val file = root.resolve("data.bin").apply { writeBytes(ByteArray(100_000) { it.toByte() }) }
        val source = PathSource(file)
        assertEquals("data.bin", source.name)
        assertEquals(100_000L, source.size)

        val reader = source.open()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(65_536)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        reader.close()
        assertContentEquals(file.readBytes(), out.toByteArray())
    }

    @Test
    fun receiveDirUsesXdgDownloadDir() {
        val home = root.resolve("home")
        val config = Files.createDirectories(home.resolve(".config"))
        config.resolve("user-dirs.dirs").writeText(
            """
            # comment
            XDG_DESKTOP_DIR="${'$'}HOME/Desktop"
            XDG_DOWNLOAD_DIR="${'$'}HOME/Téléchargements"
            """.trimIndent(),
        )
        assertEquals(home.resolve("Téléchargements/LinkDrop"), DesktopFiles.receiveDir(home, config))
    }

    @Test
    fun receiveDirFallsBackToDownloads() {
        val home = root.resolve("home")
        assertEquals(home.resolve("Downloads/LinkDrop"), DesktopFiles.receiveDir(home, home.resolve(".config")))

        // "$HOME" alone means the Downloads folder is disabled: fall back too.
        val config = Files.createDirectories(home.resolve(".config"))
        config.resolve("user-dirs.dirs").writeText("XDG_DOWNLOAD_DIR=\"${'$'}HOME\"")
        assertEquals(home.resolve("Downloads/LinkDrop"), DesktopFiles.receiveDir(home, config))
    }

    @Test
    fun absoluteXdgDownloadDirIsUsedAsIs() {
        val home = root.resolve("home")
        val config = Files.createDirectories(home.resolve(".config"))
        config.resolve("user-dirs.dirs").writeText("XDG_DOWNLOAD_DIR=\"/data/dl\"")
        assertEquals(Path.of("/data/dl/LinkDrop"), DesktopFiles.receiveDir(home, config))
    }
}
