package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.FileNames
import com.agnish.linkdrop.transfer.FileReader
import com.agnish.linkdrop.transfer.FileSink
import com.agnish.linkdrop.transfer.FileSource
import com.agnish.linkdrop.transfer.FileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

object DesktopFiles {
    /**
     * Where received files go: `LinkDrop` inside the user's Downloads folder, as configured by
     * xdg-user-dirs (`~/.config/user-dirs.dirs`), or `~/Downloads/LinkDrop`.
     */
    fun receiveDir(
        home: Path = Path.of(System.getProperty("user.home")),
        configDir: Path = System.getenv("XDG_CONFIG_HOME")?.let { Path.of(it) } ?: home.resolve(".config"),
    ): Path = (xdgDownloadDir(home, configDir) ?: home.resolve("Downloads")).resolve("LinkDrop")

    /** Reads `XDG_DOWNLOAD_DIR` from `user-dirs.dirs`. Returns null if it is missing or unusable. */
    internal fun xdgDownloadDir(home: Path, configDir: Path): Path? {
        val file = configDir.resolve("user-dirs.dirs")
        if (!Files.isRegularFile(file)) return null
        val line = runCatching { Files.readAllLines(file) }.getOrNull()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") }
            ?: return null
        val value = line.substringAfter('=').trim().removeSurrounding("\"")
        return when {
            value.startsWith("\$HOME/") -> home.resolve(value.removePrefix("\$HOME/"))
            value.startsWith("/") -> Path.of(value)
            else -> null // "$HOME" alone (Downloads disabled) or something unexpected
        }
    }
}

/**
 * Saves received files into [dir]. Data goes into a hidden `.linkdrop-<random>.part` file first;
 * only a verified file is renamed to its real name, and existing files are never overwritten.
 */
class FolderSink(private val dir: Path) : FileSink {

    /** Deletes partial files left behind by a crash or power cut. Call once at startup. */
    fun deleteLeftovers() {
        if (!Files.isDirectory(dir)) return
        Files.newDirectoryStream(dir, "$PART_PREFIX*$PART_SUFFIX").use { parts ->
            parts.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    override fun freeBytes(): Long? = runCatching {
        Files.createDirectories(dir)
        Files.getFileStore(dir).usableSpace
    }.getOrNull()

    override suspend fun create(name: String, size: Long, mimeType: String?): FileWriter = withContext(Dispatchers.IO) {
        Files.createDirectories(dir)
        val part = dir.resolve("$PART_PREFIX${UUID.randomUUID()}$PART_SUFFIX")
        val out = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).buffered(256 * 1024)
        PartFileWriter(dir, name, part, out)
    }

    private class PartFileWriter(
        private val dir: Path,
        private val name: String,
        private val part: Path,
        private val out: OutputStream,
    ) : FileWriter {
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { out.write(bytes) }

        override suspend fun commit(): String = withContext(Dispatchers.IO) {
            out.close()
            // Another program could create the same name between our check and the rename; retry then.
            repeat(MOVE_ATTEMPTS) {
                val target = FileNames.unique(name) { Files.exists(dir.resolve(it), LinkOption.NOFOLLOW_LINKS) }
                    ?: throw IOException("no free file name for $name")
                try {
                    // No REPLACE_EXISTING: fails instead of overwriting.
                    Files.move(part, dir.resolve(target))
                    return@withContext target
                } catch (e: FileAlreadyExistsException) {
                    // try the next free name
                }
            }
            throw IOException("could not find a free name for $name")
        }

        override suspend fun abort() = withContext(Dispatchers.IO) {
            runCatching { out.close() }
            Files.deleteIfExists(part)
            Unit
        }
    }

    private companion object {
        const val PART_PREFIX = ".linkdrop-"
        const val PART_SUFFIX = ".part"
        const val MOVE_ATTEMPTS = 3
    }
}

/** A file the user picked on the desktop. */
class PathSource(private val path: Path) : FileSource {
    override val name: String = path.fileName.toString()
    override val size: Long = Files.size(path)
    override val mimeType: String? = runCatching { Files.probeContentType(path) }.getOrNull()

    override suspend fun open(): FileReader = withContext(Dispatchers.IO) {
        val input = Files.newInputStream(path)
        object : FileReader {
            override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { input.read(buffer) }

            override fun close() = input.close()
        }
    }
}
