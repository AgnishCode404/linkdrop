package com.agnish.linkdrop

import com.agnish.linkdrop.transfer.PartialRecord
import com.agnish.linkdrop.transfer.Partials
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
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
        root.resolve(".linkdrop-5678.resume.tmp").writeText("half a record")
        root.resolve("keep.part").writeText("not ours")
        root.resolve("photo.jpg").writeText("x")
        FolderSink(root).cleanUpPartials()
        assertEquals(listOf("keep.part", "photo.jpg"), names(root))
    }

    private val peer = "a".repeat(64)

    @Test
    fun keptFileStaysHiddenWithItsRecord() = runBlocking {
        val writer = FolderSink(root).create("movie.mkv", 10, null)
        writer.write(byteArrayOf(1, 2, 3))
        writer.keep(peer, 3)

        val files = names(root)
        assertEquals(2, files.size, "$files")
        val part = root.resolve(files.single { it.endsWith(".part") })
        val record = root.resolve(files.single { it.endsWith(".resume") })
        assertTrue(part.name.startsWith(".linkdrop-") && record.name.startsWith(".linkdrop-"))
        assertEquals(part.name.removeSuffix(".part"), record.name.removeSuffix(".resume"))
        assertContentEquals(byteArrayOf(1, 2, 3), part.readBytes())
        val saved = assertNotNull(Partials.decode(record.readText()))
        assertEquals(PartialRecord(peer, "movie.mkv", 10, 3, saved.keptAtMillis), saved)

        // A later start keeps it.
        FolderSink(root).cleanUpPartials()
        assertEquals(files, names(root))
    }

    @Test
    fun keptFilesExpire() = runBlocking {
        FolderSink(root).create("movie.mkv", 10, null).apply { write(byteArrayOf(1)) }.keep(peer, 1)
        FolderSink(root).cleanUpPartials(nowMillis = System.currentTimeMillis() + Partials.MAX_AGE_MILLIS + 60_000)
        assertTrue(names(root).isEmpty())
    }

    @Test
    fun keptFileWithTheWrongLengthIsDeleted() = runBlocking {
        // As after a crash while writing: the data no longer matches its record.
        FolderSink(root).create("a.bin", 10, null).apply { write(byteArrayOf(1, 2)) }.keep(peer, 2)
        root.resolve(names(root).single { it.endsWith(".part") }).writeBytes(byteArrayOf(1, 2, 3))
        FolderSink(root).cleanUpPartials()
        assertTrue(names(root).isEmpty(), "${names(root)}")
    }

    @Test
    fun recordWithoutItsDataIsDeleted() = runBlocking {
        FolderSink(root).create("a.bin", 10, null).apply { write(byteArrayOf(1)) }.keep(peer, 1)
        Files.delete(root.resolve(names(root).single { it.endsWith(".part") }))
        FolderSink(root).cleanUpPartials()
        assertTrue(names(root).isEmpty(), "${names(root)}")
    }

    @Test
    fun keepingAppliesTheLimit() = runBlocking {
        val sink = FolderSink(root)
        repeat(Partials.MAX_KEPT + 2) { sink.create("f$it.bin", 10, null).apply { write(byteArrayOf(1)) }.keep(peer, 1) }
        assertEquals(Partials.MAX_KEPT, names(root).count { it.endsWith(".resume") })
        assertEquals(Partials.MAX_KEPT, names(root).count { it.endsWith(".part") })
    }

    @Test
    fun cleanupNeverTouchesAFileBeingWritten() = runBlocking {
        val sink = FolderSink(root)
        val writing = sink.create("big.bin", 4, null).apply { write(byteArrayOf(1, 2)) }
        // Another connection's file is kept meanwhile; keeping runs a cleanup.
        sink.create("other.bin", 10, null).apply { write(byteArrayOf(1)) }.keep(peer, 1)
        sink.cleanUpPartials()

        writing.write(byteArrayOf(3, 4))
        assertEquals("big.bin", writing.commit())
        assertContentEquals(byteArrayOf(1, 2, 3, 4), root.resolve("big.bin").readBytes())
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
