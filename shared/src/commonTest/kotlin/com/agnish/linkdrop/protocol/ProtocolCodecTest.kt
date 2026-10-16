package com.agnish.linkdrop.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class ProtocolCodecTest {

    private fun roundTrip(message: Message) {
        val decoded = ProtocolCodec.decode(ProtocolCodec.encode(message, id = "m1"))
        assertEquals(Decoded.Ok("m1", message), decoded)
    }

    @Test
    fun helloRoundTrips() = roundTrip(Hello("phone-1", "Pixel", PROTOCOL_VERSION, listOf("x"), 1024))

    @Test
    fun helloAckRoundTrips() = roundTrip(HelloAck("desk-1", "Arch", PROTOCOL_VERSION))

    @Test
    fun errorRoundTrips() = roundTrip(ErrorMessage(ErrorCodes.BAD_REQUEST, "nope", ref = "m0"))

    @Test
    fun errorWithoutOptionalFieldsRoundTrips() = roundTrip(ErrorMessage(ErrorCodes.MALFORMED))

    @Test
    fun encodedEnvelopeHasDocumentedShape() {
        val text = ProtocolCodec.encode(HelloAck("d", "n", PROTOCOL_VERSION), id = "abc")
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("v", "type", "id", "body"), obj.keys)
        assertEquals(PROTOCOL_VERSION, obj.getValue("v").jsonPrimitive.int)
        assertEquals("helloAck", obj.getValue("type").jsonPrimitive.content)
        assertEquals("abc", obj.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun generatedIdsAreUnique() {
        assertNotEquals(ProtocolCodec.newMessageId(), ProtocolCodec.newMessageId())
    }

    @Test
    fun unknownTypeIsReportedNotFailed() {
        val decoded = ProtocolCodec.decode("""{"v":1,"type":"futureThing","id":"t1","body":{"files":[]}}""")
        assertEquals(Decoded.UnsupportedType("t1", "futureThing"), decoded)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = """{"v":1,"type":"hello","id":"h1","extra":true,
            "body":{"deviceId":"p","deviceName":"Pixel","protocolVersion":1,"future":{"a":1}}}"""
        assertEquals(Decoded.Ok("h1", Hello("p", "Pixel", 1)), ProtocolCodec.decode(text))
    }

    @Test
    fun otherEnvelopeVersionIsUnsupported() {
        val decoded = ProtocolCodec.decode("""{"v":2,"type":"hello","id":"h1","body":{}}""")
        assertEquals(Decoded.UnsupportedVersion("h1", 2), decoded)
    }

    @Test
    fun invalidJsonIsMalformed() {
        assertIs<Decoded.Malformed>(ProtocolCodec.decode("not json"))
    }

    @Test
    fun envelopeWithoutIdIsMalformed() {
        assertIs<Decoded.Malformed>(ProtocolCodec.decode("""{"v":1,"type":"hello","body":{}}"""))
    }

    @Test
    fun bodyMissingRequiredFieldIsMalformed() {
        val decoded = ProtocolCodec.decode("""{"v":1,"type":"hello","id":"h1","body":{"deviceId":"p"}}""")
        assertIs<Decoded.Malformed>(decoded)
    }

    @Test
    fun bodyWithWrongFieldTypeIsMalformed() {
        val text = """{"v":1,"type":"hello","id":"h1",
            "body":{"deviceId":"p","deviceName":"n","protocolVersion":"one"}}"""
        assertIs<Decoded.Malformed>(ProtocolCodec.decode(text))
    }
}
