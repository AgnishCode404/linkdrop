package com.agnish.linkdrop

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.agnish.linkdrop.transfer.FileReader
import com.agnish.linkdrop.transfer.FileSink
import com.agnish.linkdrop.transfer.FileSource
import com.agnish.linkdrop.transfer.FileWriter
import com.agnish.linkdrop.transfer.PartialFile
import com.agnish.linkdrop.transfer.PartialRecord
import com.agnish.linkdrop.transfer.Partials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** Received files go to `Downloads/LinkDrop`, shown to the user as this. */
const val RECEIVE_FOLDER = "Download/LinkDrop"

/**
 * Saves received files into Downloads/LinkDrop through MediaStore: no storage permission, and the
 * app can only create its own files there (no access to anything else on the phone).
 * A file kept for resuming ([FileWriter.keep]) stays pending (hidden), and its [PartialRecord] goes
 * into the app's private `partials` folder as `<MediaStore id>.resume`.
 */
class MediaStoreSink(context: Context) : FileSink {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val recordDir = File(appContext.noBackupFilesDir, "partials")

    /** MediaStore ids of files being written or reserved for resuming; a cleanup never touches them. */
    private val active: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Cleanups and claims run one at a time, so a cleanup never deletes a file just claimed. */
    private val lock = Any()

    override fun freeBytes(): Long? =
        appContext.getExternalFilesDir(null)?.let { runCatching { StatFs(it.path).availableBytes }.getOrNull() }

    override suspend fun create(name: String, size: Long, mimeType: String?): FileWriter = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            // From the file's own extension, not the sender's claim: MediaStore renames files whose
            // extension does not match their MIME type.
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(name))
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            // Hidden from other apps until the checksum is verified (commit).
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        // MediaStore never overwrites: an existing name gets " (1)" etc.
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("could not create $name in Downloads")
        val id = ContentUris.parseId(uri)
        active += id
        val out = try {
            resolver.openOutputStream(uri, "w") ?: throw IOException("could not open $name for writing")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            active -= id
            throw e
        }
        MediaStoreWriter(id, uri, name, size, out.buffered(256 * 1024))
    }

    /**
     * Deletes what must not stay: this app's pending files left by a crash or power cut (no
     * record), and kept ones that are expired, damaged or over the limit ([Partials]). Call at startup.
     */
    fun cleanUpPartials(nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val files = idleRecordFiles()
            val keep = Partials.toKeep(intactRecords(files), nowMillis)
            files.filter { idOf(it) !in keep }.forEach { it.delete() }
            ownPendingIds().filter { it !in keep && it !in active }.forEach { id ->
                runCatching { resolver.delete(uriOf(id), null, null) }
            }
        }
    }

    override suspend fun claimPartial(peer: String, name: String, size: Long): PartialFile? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val records = intactRecords(idleRecordFiles())
            val usable = Partials.toKeep(records, System.currentTimeMillis())
            val (id, record) = records.entries
                .filter { (id, r) -> id in usable && r.peer == peer && r.name == name && r.size == size }
                .maxByOrNull { it.value.keptAtMillis }
                ?: return@withContext null
            active += id
            KeptPart(id, record)
        }
    }

    /** Record files, except those of files in use. */
    private fun idleRecordFiles(): List<File> =
        recordDir.listFiles()?.filter { it.isFile && idOf(it) !in active }.orEmpty()

    /** Records (by MediaStore id) whose data is there with the recorded length. */
    private fun intactRecords(files: List<File>): Map<Long, PartialRecord> =
        files.filter { it.name.endsWith(RECORD_SUFFIX) }.mapNotNull { file ->
            val id = idOf(file) ?: return@mapNotNull null
            val record = runCatching { Partials.decode(file.readText()) }.getOrNull()
            if (record != null && storedBytes(id) == record.bytes) id to record else null
        }.toMap()

    /** This app's pending (unfinished) files in Downloads/LinkDrop. */
    private fun ownPendingIds(): List<Long> {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? " +
                    "AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(appContext.packageName, "$RELATIVE_PATH/"))
        }
        val ids = mutableListOf<Long>()
        runCatching {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null)
                ?.use { cursor -> while (cursor.moveToNext()) ids += cursor.getLong(0) }
        }
        return ids
    }

    /** The length of a file's stored data, or null if it is gone. */
    private fun storedBytes(id: Long): Long? =
        runCatching { resolver.openFileDescriptor(uriOf(id), "r")?.use { it.statSize } }.getOrNull()

    private inner class MediaStoreWriter(
        private val id: Long,
        private val uri: Uri,
        private val name: String,
        private val size: Long,
        private val out: OutputStream,
    ) : FileWriter {
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { out.write(bytes) }

        override suspend fun commit(): String = withContext(Dispatchers.IO) {
            try {
                out.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                displayName(resolver, uri) ?: "file"
            } finally {
                active -= id
            }
        }

        override suspend fun abort() = withContext(Dispatchers.IO) {
            runCatching { out.close() }
            resolver.delete(uri, null, null)
            active -= id
            Unit
        }

        override suspend fun keep(peer: String, bytes: Long) = withContext(Dispatchers.IO) {
            try {
                out.close()
                if (!recordDir.isDirectory && !recordDir.mkdirs()) throw IOException("could not create $recordDir")
                // Written to a temporary name, then renamed: a crash never leaves half a record.
                val temp = File(recordDir, "$id$RECORD_SUFFIX.tmp")
                temp.writeText(Partials.encode(PartialRecord(peer, name, size, bytes, System.currentTimeMillis())))
                if (!temp.renameTo(File(recordDir, "$id$RECORD_SUFFIX"))) throw IOException("could not save the record")
            } finally {
                active -= id
            }
            cleanUpPartials() // applies the limit of kept files
        }
    }

    private inner class KeptPart(private val id: Long, private val record: PartialRecord) : PartialFile {
        private val uri = uriOf(id)
        override val bytes = record.bytes

        override suspend fun open(): FileReader = withContext(Dispatchers.IO) {
            StreamReader(resolver.openInputStream(uri) ?: throw IOException("could not open the kept file"))
        }

        override suspend fun resume(): FileWriter = withContext(Dispatchers.IO) {
            // The record goes first: if the app crashes from here on, this is an ordinary pending
            // file, which the next start deletes. It is rewritten if this file is kept again.
            File(recordDir, "$id$RECORD_SUFFIX").delete()
            val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("could not open the kept file")
            val out = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            try {
                out.channel.truncate(bytes)
                out.channel.position(bytes)
            } catch (e: Exception) {
                out.close()
                throw e
            }
            MediaStoreWriter(id, uri, record.name, record.size, out.buffered(256 * 1024))
        }

        override suspend fun release() {
            active -= id
        }

        override suspend fun discard() = withContext(Dispatchers.IO) {
            File(recordDir, "$id$RECORD_SUFFIX").delete()
            runCatching { resolver.delete(uri, null, null) }
            active -= id
            Unit
        }
    }

    private companion object {
        val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/LinkDrop"
        const val RECORD_SUFFIX = ".resume"

        fun uriOf(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)

        /** The MediaStore id in a record's file name (`<id>.resume` or `<id>.resume.tmp`). */
        fun idOf(file: File): Long? = file.name.substringBefore('.').toLongOrNull()

        fun mimeTypeFor(name: String): String {
            val extension = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
    }
}

/** A file chosen in the system picker or shared from another app. */
class UriSource private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
    override val mimeType: String?,
) : FileSource {

    override suspend fun open(): FileReader = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw IOException("could not open $name")
        StreamReader(input)
    }

    companion object {
        /**
         * Reads the file's name and size. Some apps share files without a size; those are read once
         * to measure them, since an offer must state each file's size up front.
         */
        suspend fun from(context: Context, uri: Uri): UriSource = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var name: String? = null
            var size: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
            val knownSize = size?.takeIf { it >= 0 } ?: measure(resolver, uri)
            UriSource(resolver, uri, name ?: uri.lastPathSegment ?: "file", knownSize, resolver.getType(uri))
        }

        private fun measure(resolver: ContentResolver, uri: Uri): Long {
            val input = resolver.openInputStream(uri) ?: throw IOException("could not open $uri")
            input.use {
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) return total
                    total += n
                }
            }
        }
    }
}

private class StreamReader(private val input: InputStream) : FileReader {
    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { input.read(buffer) }

    override fun close() = input.close()
}

private fun displayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
