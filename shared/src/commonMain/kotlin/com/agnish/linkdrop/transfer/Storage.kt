package com.agnish.linkdrop.transfer

/** A file the user chose to send. Each app implements this for its own file APIs. */
interface FileSource {
    val name: String
    val size: Long
    val mimeType: String?

    /** Opens the file for reading from the start. */
    suspend fun open(): FileReader
}

interface FileReader {
    /** Reads up to [buffer].size bytes. Returns the number read, or -1 at the end of the file. */
    suspend fun read(buffer: ByteArray): Int

    fun close()
}

/** Where received files go: one folder chosen by the app (PROTOCOL.md, "Receiver rules"). */
interface FileSink {
    /** Free space for received files, or null if unknown. */
    fun freeBytes(): Long?

    /**
     * Starts a new file. [name] is already cleaned by [com.agnish.linkdrop.protocol.FileNames.clean];
     * the sink must not overwrite an existing file (it picks `name (1).ext` etc. instead), and the
     * file must not be visible under its final name until [FileWriter.commit].
     */
    suspend fun create(name: String, size: Long, mimeType: String?): FileWriter
}

interface FileWriter {
    suspend fun write(bytes: ByteArray)

    /** Makes the complete, verified file visible. Returns where it was saved, for the user. */
    suspend fun commit(): String

    /** Deletes the partial file. */
    suspend fun abort()

    /**
     * The connection was lost mid-file: keeps the data written so far, still hidden, with a
     * [PartialRecord] of [peer] (a paired device's certificate fingerprint) and [bytes], so that
     * device can resume it. Kept files follow the [Partials] rules.
     */
    suspend fun keep(peer: String, bytes: Long)
}
