package com.agnish.linkdrop.transfer

import java.security.MessageDigest

actual fun newSha256(): Sha256 = object : Sha256 {
    private val digest = MessageDigest.getInstance("SHA-256")

    override fun update(data: ByteArray, offset: Int, length: Int) = digest.update(data, offset, length)

    override fun hexDigest(): String = digest.digest().toHex()
}
