package com.agnish.linkdrop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HandshakeTest {

    private val desktop = LocalDevice("desk-1", "Arch")

    @Test
    fun compatibleHelloGetsAck() {
        val reply = Handshake.respondTo(Hello("p", "Pixel", PROTOCOL_VERSION), "h1", desktop)
        assertEquals(desktop.helloAck(), reply)
    }

    @Test
    fun otherProtocolVersionGetsVersionMismatch() {
        val reply = Handshake.respondTo(Hello("p", "Pixel", PROTOCOL_VERSION + 1), "h1", desktop)
        val error = assertIs<ErrorMessage>(reply)
        assertEquals(ErrorCodes.VERSION_MISMATCH, error.code)
        assertEquals("h1", error.ref)
    }

    @Test
    fun invalidIdentityGetsBadRequest() {
        val tooLong = "x".repeat(MAX_DEVICE_FIELD_LENGTH + 1)
        for (hello in listOf(
            Hello("", "Pixel", PROTOCOL_VERSION),
            Hello("p", " ", PROTOCOL_VERSION),
            Hello(tooLong, "Pixel", PROTOCOL_VERSION),
            Hello("p", tooLong, PROTOCOL_VERSION),
            Hello("p", "Pix\nel", PROTOCOL_VERSION),
        )) {
            val error = assertIs<ErrorMessage>(Handshake.respondTo(hello, "h1", desktop), "for $hello")
            assertEquals(ErrorCodes.BAD_REQUEST, error.code)
        }
    }

    @Test
    fun clientAcceptsValidAck() {
        assertNull(Handshake.checkAck(desktop.helloAck()))
    }

    @Test
    fun clientRejectsAckWithOtherVersion() {
        assertNotNull(Handshake.checkAck(HelloAck("d", "n", PROTOCOL_VERSION + 1)))
    }

    @Test
    fun clientRejectsAckWithInvalidName() {
        assertNotNull(Handshake.checkAck(HelloAck("d", "", PROTOCOL_VERSION)))
    }
}
