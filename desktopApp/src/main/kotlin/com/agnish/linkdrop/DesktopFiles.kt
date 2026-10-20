package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.FileNames
import com.agnish.linkdrop.transfer.FileReader
import com.agnish.linkdrop.transfer.FileSink
import com.agnish.linkdrop.transfer.FileSource
import com.agnish.linkdrop.transfer.FileWriter
import com.agnish.linkdrop.transfer.PartialFile
import com.agnish.linkdrop.transfer.PartialRecord
import com.agnish.linkdrop.transfer.Partials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name

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
 * A file kept for resuming ([FileWriter.keep]) also gets a `.linkdrop-<random>.resume` record.
 */
class FolderSink(private val dir: Path) : FileSink {

    /**
     * Keys of files being written or reserved for resuming right now (by any connection); a
     * cleanup never touches them.
     */
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Cleanups and claims run one at a time, so a cleanup never deletes a file just claimed. */
    private val lock = Any()

    /**
     * Deletes what must not stay: partial files left by a crash or power cut (no record), and kept
     * ones that are expired, damaged or over the limit ([Partials]). Call at startup.
     */
    fun cleanUpPartials(nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (!Files.isDirectory(dir)) return
            val ours = ourIdleFiles()
            val keep = Partials.toKeep(intactRecords(ours), nowMillis)
            ours.filterNot { keyOf(it) in keep }.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    override suspend fun claimPartial(peer: String, name: String, size: Long): PartialFile? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!Files.isDirectory(dir)) return@withContext null
            val records = intactRecords(ourIdleFiles())
            val usable = Partials.toKeep(records, System.currentTimeMillis())
            val (key, record) = records.entries
                .filter { (key, r) -> key in usable && r.peer == peer && r.name == name && r.size == size }
                .maxByOrNull { it.value.keptAtMillis }
                ?: return@withContext null
            active += key
            KeptPart(key, record)
        }
    }

    /** This sink's hidden files, except those in use. */
    private fun ourIdleFiles(): List<Path> = Files.newDirectoryStream(dir, "$PREFIX*").use { entries ->
        entries.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && keyOf(it) !in active }
    }

    /** Records (by key) whose data is there with the recorded length. */
    private fun intactRecords(ours: List<Path>): Map<String, PartialRecord> {
        val parts = ours.filter { it.name.endsWith(PART_SUFFIX) }.associateBy { keyOf(it) }
        return ours.filter { it.name.endsWith(RECORD_SUFFIX) }.mapNotNull { file ->
            val record = runCatching { Partials.decode(Files.readString(file)) }.getOrNull()
            val part = parts[keyOf(file)]
            val intact = record != null && part != null && runCatching { Files.size(part) }.getOrNull() == record.bytes
            if (intact) keyOf(file) to record else null
        }.toMap()
    }

    override fun freeBytes(): Long? = runCatching {
        Files.createDirectories(dir)
        Files.getFileStore(dir).usableSpace
    }.getOrNull()

    override suspend fun create(name: String, size: Long, mimeType: String?): FileWriter = withContext(Dispatchers.IO) {
        Files.createDirectories(dir)
        val key = "$PREFIX${UUID.randomUUID()}"
        active += key
        try {
            val part = dir.resolve("$key$PART_SUFFIX")
            val out = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            PartFileWriter(name, size, key, out.buffered(256 * 1024))
        } catch (e: Exception) {
            active -= key
            throw e
        }
    }

    private inner class PartFileWriter(
        private val name: String,
        private val size: Long,
        private val key: String,
        private val out: OutputStream,
    ) : FileWriter {
        private val part = dir.resolve("$key$PART_SUFFIX")

        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { out.write(bytes) }

        override suspend fun commit(): String = withContext(Dispatchers.IO) {
            try {
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
            } finally {
                active -= key
            }
        }

        override suspend fun abort() = withContext(Dispatchers.IO) {
            runCatching { out.close() }
            Files.deleteIfExists(part)
            active -= key
            Unit
        }

        override suspend fun keep(peer: String, bytes: Long) = withContext(Dispatchers.IO) {
            try {
                out.close()
                // Written to a temporary name, then renamed: a crash never leaves half a record.
                val record = dir.resolve("$key$RECORD_SUFFIX")
                val temp = dir.resolve("$key$RECORD_SUFFIX.tmp")
                Files.writeString(temp, Partials.encode(PartialRecord(peer, name, size, bytes, System.currentTimeMillis())))
                Files.move(temp, record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                active -= key
            }
            cleanUpPartials() // applies the limit of kept files
        }
    }

    private inner class KeptPart(private val key: String, private val record: PartialRecord) : PartialFile {
        private val part = dir.resolve("$key$PART_SUFFIX")
        override val bytes = record.bytes

        override suspend fun open(): FileReader = withContext(Dispatchers.IO) { readerOf(part) }

        override suspend fun resume(): FileWriter = withContext(Dispatchers.IO) {
            // The record goes first: if the app crashes from here on, this is an ordinary unfinished
            // file, which the next start deletes. Losing the record again is fine; it is rewritten
            // if this file is kept again.
            Files.deleteIfExists(dir.resolve("$key$RECORD_SUFFIX"))
            val channel = FileChannel.open(part, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            try {
                channel.truncate(bytes)
                channel.position(bytes)
            } catch (e: Exception) {
                channel.close()
                throw e
            }
            PartFileWriter(record.name, record.size, key, Channels.newOutputStream(channel).buffered(256 * 1024))
        }

        override suspend fun release() {
            active -= key
        }

        override suspend fun discard() = withContext(Dispatchers.IO) {
            runCatching { Files.deleteIfExists(dir.resolve("$key$RECORD_SUFFIX")) }
            runCatching { Files.deleteIfExists(part) }
            active -= key
            Unit
        }
    }

    private companion object {
        const val PREFIX = ".linkdrop-"
        const val PART_SUFFIX = ".part"
        const val RECORD_SUFFIX = ".resume"
        const val MOVE_ATTEMPTS = 3

        /** `.linkdrop-<random>`, the same for its `.part`, `.resume` and `.resume.tmp` files. */
        fun keyOf(file: Path): String = "." + file.name.removePrefix(".").substringBefore('.')
    }
}

/** A file the user picked on the desktop. */
class PathSource(private val path: Path) : FileSource {
    override val name: String = path.fileName.toString()
    override val size: Long = Files.size(path)
    override val mimeType: String? = runCatching { Files.probeContentType(path) }.getOrNull()

    override suspend fun open(): FileReader = withContext(Dispatchers.IO) { readerOf(path) }
}

private fun readerOf(path: Path): FileReader {
    val input = Files.newInputStream(path)
    return object : FileReader {
        override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { input.read(buffer) }

        override fun close() = input.close()
    }
}
