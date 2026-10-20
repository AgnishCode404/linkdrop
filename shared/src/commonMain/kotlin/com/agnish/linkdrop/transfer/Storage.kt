package com.agnish.linkdrop.transfer

/** A file the user chose to send. Each app implements this for its own file APIs. */
interface FileSource {
    val name: String
    val size: Long
    val mimeType: String?

    /** Opens the file for reading from the start (when resuming, too: the checksum covers it all). */
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

    /**
     * Finds a file kept for resuming ([FileWriter.keep]) from [peer] with this cleaned [name] and
     * [size], and reserves it, so no cleanup or other transfer touches it. Null if there is none.
     */
    suspend fun claimPartial(peer: String, name: String, size: Long): PartialFile?
}

/** A kept partial file, reserved by [FileSink.claimPartial]. Exactly one of the last three is called. */
interface PartialFile {
    /** How much is stored. */
    val bytes: Long

    /** Reads the stored data from the start, to recompute its checksum. */
    suspend fun open(): FileReader

    /** Continues the file after its first [bytes]. From then on it is an ordinary unfinished file. */
    suspend fun resume(): FileWriter

    /** Not resumed now (e.g. cancelled before it started): stays kept, as before, for a later try. */
    suspend fun release()

    /** Not wanted any more (the sender started the file over): deletes it. */
    suspend fun discard()
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
