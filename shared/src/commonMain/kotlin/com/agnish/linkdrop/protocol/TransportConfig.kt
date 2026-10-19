package com.agnish.linkdrop.protocol

/**
 * How a connection is secured. Every connection in both apps is set up from one of these,
 * and [choose] is the only place that picks one.
 */
sealed interface TransportConfig {
    /** `ws://`, unencrypted. Development builds only; never chosen when TLS is possible. */
    data object Plaintext : TransportConfig

    /**
     * `wss://` with mutual TLS (PROTOCOL.md, "Security and pairing"). On the phone,
     * [pinnedCertSha256] is the paired desktop's certificate fingerprint; any other certificate is
     * refused. Null means not pinned: a desktop not paired yet is accepted only after both users
     * confirmed the pairing code. The desktop server does not use the field.
     */
    data class Tls(val pinnedCertSha256: String? = null) : TransportConfig

    val scheme: String
        get() = when (this) {
            Plaintext -> "ws"
            is Tls -> "wss"
        }

    companion object {
        /**
         * Picks the transport. A pinned certificate always means TLS, with no fallback to
         * plaintext; plaintext only when this build explicitly allows it; otherwise TLS.
         */
        fun choose(allowPlaintext: Boolean, pinnedCertSha256: String?): TransportConfig = when {
            pinnedCertSha256 != null -> Tls(pinnedCertSha256)
            allowPlaintext -> Plaintext
            else -> Tls(null)
        }
    }
}
