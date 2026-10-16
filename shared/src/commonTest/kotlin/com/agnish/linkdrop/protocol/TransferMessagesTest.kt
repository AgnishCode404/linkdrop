package com.agnish.linkdrop.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TransferMessagesTest {

    private fun roundTrip(message: Message) {
        assertEquals(Decoded.Ok("m1", message), ProtocolCodec.decode(ProtocolCodec.encode(message, id = "m1")))
    }

    private fun typeOf(message: Message): String =
        Json.parseToJsonElement(ProtocolCodec.encode(message)).jsonObject.getValue("type").jsonPrimitive.content

    private val offer = TransferOffer(
        "t1",
        listOf(OfferedFile(0, "a.jpg", 1234, "image/jpeg"), OfferedFile(1, "b.txt", 0)),
    )

    @Test
    fun everyTransferMessageRoundTrips() {
        for (message in listOf(
            offer,
            TransferResponse("t1", listOf(FileDecision(0, accept = true), FileDecision(1, accept = false, resumeOffset = 7))),
            FileBegin("t1", 0, offset = 0),
            FileEnd("t1", 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            FileResult("t1", 0, ok = true),
            FileResult("t1", 1, ok = false, error = TransferErrors.CHECKSUM_MISMATCH),
            Cancel("t1"),
            Cancel("t1", fileId = 1),
            TransferDone("t1"),
        )) {
            roundTrip(message)
        }
    }

    @Test
    fun typeNamesMatchTheSpec() {
        assertEquals("transferOffer", typeOf(offer))
        assertEquals("transferResponse", typeOf(TransferResponse("t", emptyList())))
        assertEquals("fileBegin", typeOf(FileBegin("t", 0)))
        assertEquals("fileEnd", typeOf(FileEnd("t", 0, "00")))
        assertEquals("fileResult", typeOf(FileResult("t", 0, ok = true)))
        assertEquals("cancel", typeOf(Cancel("t")))
        assertEquals("transferDone", typeOf(TransferDone("t")))
    }

    @Test
    fun optionalFieldsMayBeOmitted() {
        val text = """{"v":1,"type":"transferOffer","id":"o1",
            "body":{"transferId":"t1","files":[{"fileId":3,"name":"x","size":5}]}}"""
        assertEquals(Decoded.Ok("o1", TransferOffer("t1", listOf(OfferedFile(3, "x", 5)))), ProtocolCodec.decode(text))

        val response = """{"v":1,"type":"transferResponse","id":"r1",
            "body":{"transferId":"t1","files":[{"fileId":3,"accept":true}]}}"""
        assertEquals(
            Decoded.Ok("r1", TransferResponse("t1", listOf(FileDecision(3, accept = true, resumeOffset = 0)))),
            ProtocolCodec.decode(response),
        )
    }

    @Test
    fun missingRequiredFieldIsMalformed() {
        assertIs<Decoded.Malformed>(ProtocolCodec.decode("""{"v":1,"type":"fileEnd","id":"e1","body":{"transferId":"t1","fileId":0}}"""))
        assertIs<Decoded.Malformed>(ProtocolCodec.decode("""{"v":1,"type":"transferOffer","id":"o1","body":{"files":[]}}"""))
    }
}
