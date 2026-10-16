package com.agnish.linkdrop.protocol

/** Rules for turning an untrusted [OfferedFile.name] into a safe local file name (PROTOCOL.md). */
object FileNames {
    /** Characters not allowed in names on FAT/exFAT storage (e.g. SD cards); replaced with `_`. */
    private const val RESERVED = "\"*:<>?|"

    /**
     * Returns a safe file name, or null if [untrusted] has to be rejected:
     * - only the part after the last `/` or `\` is kept,
     * - names containing control characters or invisible formatting characters (such as the
     *   right-to-left override, used to disguise `exe.jpg` as `gpj.exe`) are rejected,
     * - leading dots are removed (no hidden files), reserved characters become `_`,
     * - surrounding spaces are trimmed, and names over [TransferLimits.MAX_NAME_BYTES] UTF-8
     *   bytes are shortened, keeping the extension,
     * - empty results, `.` and `..` are rejected.
     */
    fun clean(untrusted: String): String? {
        val lastPart = untrusted.substringAfterLast('/').substringAfterLast('\\')
        if (lastPart.any { it.isISOControl() || it.category == CharCategory.FORMAT }) return null
        val replaced = lastPart.map { if (it in RESERVED) '_' else it }.joinToString("")
        val name = replaced.trim().trimStart('.').trim()
        if (name.isEmpty()) return null
        return shorten(name)
    }

    /**
     * Returns [name], or `name (1).ext`, `name (2).ext`, … — the first that [exists] says is free.
     * Returns null if no free name was found after [maxAttempts].
     */
    fun unique(name: String, maxAttempts: Int = 10_000, exists: (String) -> Boolean): String? {
        if (!exists(name)) return name
        val (base, extension) = split(name)
        for (n in 1..maxAttempts) {
            // Shorten the base, not the whole name, so " (n)" is never cut off again.
            val candidate = fit(base, " ($n)$extension")
            if (!exists(candidate)) return candidate
        }
        return null
    }

    /** `photo.jpg` → (`photo`, `.jpg`). A name without a dot, or starting with one, has no extension. */
    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    private fun shorten(name: String): String {
        if (name.encodeToByteArray().size <= TransferLimits.MAX_NAME_BYTES) return name
        val (base, ext) = split(name)
        // Keep the extension only if it is short; a 200-byte "extension" is not one.
        return fit(base, if (ext.encodeToByteArray().size <= 16) ext else "")
    }

    /** [base] cut down (at a character boundary) so that base + [suffix] fits the byte limit. */
    private fun fit(base: String, suffix: String): String {
        val budget = TransferLimits.MAX_NAME_BYTES - suffix.encodeToByteArray().size
        val kept = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < base.length) {
            // Never split a surrogate pair (emoji and other characters outside the BMP).
            val end = if (base[i].isHighSurrogate() && i + 1 < base.length) i + 2 else i + 1
            val piece = base.substring(i, end)
            val size = piece.encodeToByteArray().size
            if (bytes + size > budget) break
            kept.append(piece)
            bytes += size
            i = end
        }
        return kept.toString().trimEnd() + suffix
    }
}
