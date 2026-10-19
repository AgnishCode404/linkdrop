package com.agnish.linkdrop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class TransportConfigTest {

    @Test
    fun plaintextOnlyWhenAllowedAndNoPin() {
        assertEquals(TransportConfig.Plaintext, TransportConfig.choose(allowPlaintext = true, pinnedCertSha256 = null))
    }

    @Test
    fun pinnedCertificateAlwaysMeansTlsEvenWhenPlaintextAllowed() {
        assertEquals(TransportConfig.Tls("ab12"), TransportConfig.choose(allowPlaintext = true, pinnedCertSha256 = "ab12"))
        assertEquals(TransportConfig.Tls("ab12"), TransportConfig.choose(allowPlaintext = false, pinnedCertSha256 = "ab12"))
    }

    @Test
    fun withoutPlaintextItIsAlwaysTlsUnpinnedUntilPaired() {
        assertEquals(TransportConfig.Tls(null), TransportConfig.choose(allowPlaintext = false, pinnedCertSha256 = null))
    }

    @Test
    fun schemes() {
        assertEquals("ws", TransportConfig.Plaintext.scheme)
        assertEquals("wss", TransportConfig.Tls("ab12").scheme)
    }
}
