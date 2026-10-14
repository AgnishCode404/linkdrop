package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.ErrorCodes
import com.agnish.linkdrop.protocol.ErrorMessage
import com.agnish.linkdrop.protocol.Hello
import com.agnish.linkdrop.protocol.HelloAck
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.Message
import com.agnish.linkdrop.protocol.PROTOCOL_VERSION
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.TransportConfig
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelloServerTest {

    private val desktop = LocalDevice("desk-1", "Arch")
    private val logLines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun serverTest(block: suspend DefaultClientWebSocketSession.() -> Unit) = testApplication {
        application { linkDropModule(desktop) { logLines += it } }
        wsClient().webSocket(SESSION_PATH) { block() }
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(WebSockets) }

    private suspend fun DefaultClientWebSocketSession.sendText(text: String) = send(Frame.Text(text))

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): Message {
        val frame = assertIs<Frame.Text>(incoming.receive())
        return assertIs<Decoded.Ok>(ProtocolCodec.decode(frame.readText())).message
    }

    private fun hello(name: String = "Pixel", version: Int = PROTOCOL_VERSION) =
        ProtocolCodec.encode(Hello("phone-1", name, version), id = "h1")

    /** After an error the server closes; the next receive ends instead of returning a frame. */
    private suspend fun DefaultClientWebSocketSession.assertClosed() {
        assertNull(incoming.receiveCatching().getOrNull())
    }

    @Test
    fun tlsConfigRefusesToStartInsteadOfServingPlaintext() {
        val server = HelloServer(desktop, port = 0, TransportConfig.Tls("ab12")) { logLines += it }
        val error = assertFailsWith<IllegalStateException> { server.start() }
        assertEquals("TLS is not implemented yet", error.message)
    }

    @Test
    fun helloGetsAckWithServerIdentity() = serverTest {
        sendText(hello())
        assertEquals(desktop.helloAck(), receiveMessage())
        assertTrue(logLines.any { "Hello from \"Pixel\"" in it }, "log: $logLines")
    }

    @Test
    fun wrongProtocolVersionIsRejectedAndClosed() = serverTest {
        sendText(hello(version = PROTOCOL_VERSION + 1))
        val error = assertIs<ErrorMessage>(receiveMessage())
        assertEquals(ErrorCodes.VERSION_MISMATCH, error.code)
        assertEquals("h1", error.ref)
        assertClosed()
    }

    @Test
    fun invalidDeviceNameIsRejected() = serverTest {
        sendText(hello(name = "bad\nname"))
        assertEquals(ErrorCodes.BAD_REQUEST, assertIs<ErrorMessage>(receiveMessage()).code)
        assertClosed()
    }

    @Test
    fun malformedFirstFrameIsRejected() = serverTest {
        sendText("not json")
        assertEquals(ErrorCodes.MALFORMED, assertIs<ErrorMessage>(receiveMessage()).code)
        assertClosed()
    }

    @Test
    fun otherMessageBeforeHelloIsRejected() = serverTest {
        sendText(ProtocolCodec.encode(HelloAck("x", "y", PROTOCOL_VERSION)))
        assertEquals(ErrorCodes.BAD_REQUEST, assertIs<ErrorMessage>(receiveMessage()).code)
        assertClosed()
    }

    @Test
    fun unknownTypeAfterHandshakeKeepsSessionOpen() = serverTest {
        sendText(hello())
        assertIs<HelloAck>(receiveMessage())

        sendText("""{"v":1,"type":"transferOffer","id":"t1","body":{}}""")
        val error = assertIs<ErrorMessage>(receiveMessage())
        assertEquals(ErrorCodes.UNSUPPORTED_TYPE, error.code)
        assertEquals("t1", error.ref)

        // Still open: a second unknown message gets a second answer.
        sendText("""{"v":1,"type":"cancel","id":"t2","body":{}}""")
        assertEquals("t2", assertIs<ErrorMessage>(receiveMessage()).ref)
    }

    @Test
    fun repeatedMalformedFramesCloseTheSession() = serverTest {
        sendText(hello())
        assertIs<HelloAck>(receiveMessage())

        repeat(MAX_MALFORMED_FRAMES) {
            sendText("not json")
            assertEquals(ErrorCodes.MALFORMED, assertIs<ErrorMessage>(receiveMessage()).code)
        }
        assertClosed()
    }

    @Test
    fun fewerMalformedFramesKeepSessionOpen() = serverTest {
        sendText(hello())
        assertIs<HelloAck>(receiveMessage())

        repeat(MAX_MALFORMED_FRAMES - 1) {
            sendText("not json")
            assertEquals(ErrorCodes.MALFORMED, assertIs<ErrorMessage>(receiveMessage()).code)
        }
        // Still open: an unknown type is still answered.
        sendText("""{"v":1,"type":"cancel","id":"t1","body":{}}""")
        assertEquals("t1", assertIs<ErrorMessage>(receiveMessage()).ref)
    }

    @Test
    fun binaryFrameWithoutTransferFeatureIsRejected() = serverTest {
        sendText(hello())
        assertIs<HelloAck>(receiveMessage())
        send(Frame.Binary(true, byteArrayOf(0, 0, 0, 1, 42)))
        assertEquals(ErrorCodes.BAD_REQUEST, assertIs<ErrorMessage>(receiveMessage()).code)
        assertClosed()
    }
}
