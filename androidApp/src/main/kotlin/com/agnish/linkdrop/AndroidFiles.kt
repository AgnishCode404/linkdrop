package com.agnish.linkdrop

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.agnish.linkdrop.transfer.FileReader
import com.agnish.linkdrop.transfer.FileSink
import com.agnish.linkdrop.transfer.FileSource
import com.agnish.linkdrop.transfer.FileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Received files go to `Downloads/LinkDrop`, shown to the user as this. */
const val RECEIVE_FOLDER = "Download/LinkDrop"

/**
 * Saves received files into Downloads/LinkDrop through MediaStore: no storage permission, and the
 * app can only create its own files there (no access to anything else on the phone).
 */
class MediaStoreSink(context: Context) : FileSink {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override fun freeBytes(): Long? =
        appContext.getExternalFilesDir(null)?.let { runCatching { StatFs(it.path).availableBytes }.getOrNull() }

    override suspend fun create(name: String, size: Long, mimeType: String?): FileWriter = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            // From the file's own extension, not the sender's claim: MediaStore renames files whose
            // extension does not match their MIME type.
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(name))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LinkDrop")
            // Hidden from other apps until the checksum is verified (commit).
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        // MediaStore never overwrites: an existing name gets " (1)" etc.
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("could not create $name in Downloads")
        val out = try {
            resolver.openOutputStream(uri, "w") ?: throw IOException("could not open $name for writing")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        MediaStoreWriter(resolver, uri, out.buffered(256 * 1024))
    }

    private class MediaStoreWriter(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val out: OutputStream,
    ) : FileWriter {
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { out.write(bytes) }

        override suspend fun commit(): String = withContext(Dispatchers.IO) {
            out.close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            displayName(resolver, uri) ?: "file"
        }

        override suspend fun abort() = withContext(Dispatchers.IO) {
            runCatching { out.close() }
            resolver.delete(uri, null, null)
            Unit
        }
    }

    private companion object {
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

    private class StreamReader(private val input: InputStream) : FileReader {
        override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { input.read(buffer) }

        override fun close() = input.close()
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

private fun displayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
