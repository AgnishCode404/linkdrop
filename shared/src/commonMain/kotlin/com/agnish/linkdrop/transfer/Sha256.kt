package com.agnish.linkdrop.transfer

/** Incremental SHA-256, backed by the platform's standard implementation (no custom crypto). */
interface Sha256 {
    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset)

    /** Lowercase hex digest. Call once, after all updates. */
    fun hexDigest(): String
}

expect fun newSha256(): Sha256

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xff
        out[i * 2] = digits[b ushr 4]
        out[i * 2 + 1] = digits[b and 0x0f]
    }
    return out.concatToString()
}
