package com.agnish.linkdrop.transfer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A file whose transfer was interrupted by a lost connection, kept so the same paired device can
 * resume it (PROTOCOL.md, "Resuming"). Each app stores one next to its partial data.
 */
@Serializable
data class PartialRecord(
    /** Certificate fingerprint of the paired device that sent it. */
    val peer: String,
    /** The cleaned file name the data was being saved as. */
    val name: String,
    /** The file's full size, as offered. */
    val size: Long,
    /** Bytes received and stored. A record whose data has another length is damaged. */
    val bytes: Long,
    /** When it was kept, in milliseconds since 1970 (UTC). */
    val keptAtMillis: Long,
)

/** The rules for kept partial files, the same on both apps. */
object Partials {
    /** Kept for at most 7 days. Android deletes unfinished MediaStore files after about as long. */
    const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** At most this many are kept; beyond it the oldest go first. */
    const val MAX_KEPT = 16

    /** A record newer than now by more than this (the clock was moved back) is not trusted. */
    private const val CLOCK_SLACK_MILLIS = 24L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(record: PartialRecord): String = json.encodeToString(PartialRecord.serializer(), record)

    /** Returns null for anything that is not a sound record. */
    fun decode(text: String): PartialRecord? =
        runCatching { json.decodeFromString(PartialRecord.serializer(), text) }.getOrNull()?.takeIf { it.isSound() }

    private fun PartialRecord.isSound() = peer.isNotBlank() && name.isNotBlank() && bytes in 1 until size

    /**
     * Which of [records] to keep at [nowMillis]: sound, not expired, and the newest [MAX_KEPT].
     * Each app deletes the data of every record not returned, and any data without a record.
     */
    fun <K> toKeep(records: Map<K, PartialRecord>, nowMillis: Long): Set<K> =
        records.entries
            .filter { (_, r) -> r.isSound() && r.keptAtMillis in (nowMillis - MAX_AGE_MILLIS)..(nowMillis + CLOCK_SLACK_MILLIS) }
            .sortedByDescending { it.value.keptAtMillis }
            .take(MAX_KEPT)
            .map { it.key }
            .toSet()
}
