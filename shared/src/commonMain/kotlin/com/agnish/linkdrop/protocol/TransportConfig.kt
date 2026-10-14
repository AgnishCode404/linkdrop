package com.agnish.linkdrop.protocol

/**
 * How a connection is secured. Every connection in both apps is set up from one of these,
 * and [choose] is the only place that picks one.
 */
sealed interface TransportConfig {
    /** `ws://`, unencrypted. Development builds only; never chosen when TLS is possible. */
    data object Plaintext : TransportConfig

    /** `wss://` with the peer's certificate pinned during pairing (Slice D, not implemented yet). */
    data class Tls(val pinnedCertSha256: String) : TransportConfig

    val scheme: String
        get() = when (this) {
            Plaintext -> "ws"
            is Tls -> "wss"
        }

    companion object {
        /**
         * Picks the transport for this build. A pinned certificate always means TLS, with no
         * fallback to plaintext. Returns null when the build allows no transport (a release
         * build before pairing exists).
         */
        fun choose(allowPlaintext: Boolean, pinnedCertSha256: String?): TransportConfig? = when {
            pinnedCertSha256 != null -> Tls(pinnedCertSha256)
            allowPlaintext -> Plaintext
            else -> null
        }

        /** Shown to the user when [choose] returns null. */
        const val NO_TRANSPORT_MESSAGE =
            "This build has plaintext disabled and secure connections (TLS) are not implemented yet."
    }
}
