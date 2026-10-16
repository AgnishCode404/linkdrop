package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.ErrorCodes
import com.agnish.linkdrop.protocol.ErrorMessage
import com.agnish.linkdrop.protocol.Handshake
import com.agnish.linkdrop.protocol.Hello
import com.agnish.linkdrop.protocol.HelloResult
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.MAX_FRAME_BYTES
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.ServerAddress
import com.agnish.linkdrop.protocol.TransportConfig
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs [HelloClient] with its real OkHttp engine over real sockets against a local fake server.
 * (Ktor's in-memory test client would not exercise OkHttp.)
 */
class HelloClientTest {

    private val phone = LocalDevice("phone-1", "Test phone")
    private val desktop = LocalDevice("desk-1", "Fake desktop")
    private val receivedHellos = CopyOnWriteArrayList<Hello>()

    /** Connects, turns the outcome into a [HelloResult] (as the UI shows it), and hangs up. */
    private suspend fun connectOnce(address: ServerAddress, local: LocalDevice, transport: TransportConfig): HelloResult =
        when (val result = HelloClient.connect(address, local, transport)) {
            is ConnectResult.Opened -> {
                result.channel.close()
                HelloResult.Connected(result.ack.deviceName)
            }
            is ConnectResult.Failed -> HelloResult.Failed(result.reason)
        }

    /** Starts a fake server whose replies are decided by [reply], runs the client once, stops the server. */
    private fun sayHelloTo(reply: suspend DefaultWebSocketServerSession.(hello: Decoded) -> Unit): HelloResult {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket(SESSION_PATH) {
                    val first = incoming.receive() as Frame.Text
                    val decoded = ProtocolCodec.decode(first.readText())
                    (decoded as? Decoded.Ok)?.message?.let { if (it is Hello) receivedHellos += it }
                    reply(decoded)
                }
            }
        }.start(wait = false)
        try {
            return runBlocking {
                val port = server.engine.resolvedConnectors().first().port
                connectOnce(ServerAddress("127.0.0.1", port), phone, TransportConfig.Plaintext)
            }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    private suspend fun DefaultWebSocketServerSession.sendText(text: String) = send(Frame.Text(text))

    @Test
    fun connectsAndReportsServerName() {
        val result = sayHelloTo { decoded ->
            val ok = decoded as Decoded.Ok
            sendText(ProtocolCodec.encode(Handshake.respondTo(ok.message as Hello, ok.id, desktop)))
        }
        assertEquals(HelloResult.Connected("Fake desktop"), result)
        assertEquals(listOf(phone.hello()), receivedHellos)
    }

    @Test
    fun serverErrorIsReported() {
        val result = sayHelloTo {
            sendText(ProtocolCodec.encode(ErrorMessage(ErrorCodes.VERSION_MISMATCH, "expected 2")))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ErrorCodes.VERSION_MISMATCH))
        }
        assertEquals(HelloResult.Failed("server refused: version_mismatch (expected 2)"), result)
    }

    @Test
    fun oversizedReplyIsRejected() {
        val result = sayHelloTo { sendText("x".repeat(MAX_FRAME_BYTES + 1)) }
        val failed = assertIs<HelloResult.Failed>(result)
        assertTrue("too large" in failed.reason, failed.reason)
    }

    @Test
    fun replyAtSizeLimitIsAccepted() {
        // A valid ack padded with spaces (valid JSON whitespace) to exactly the limit.
        val result = sayHelloTo { decoded ->
            val ok = decoded as Decoded.Ok
            val ack = ProtocolCodec.encode(Handshake.respondTo(ok.message as Hello, ok.id, desktop))
            sendText(ack + " ".repeat(MAX_FRAME_BYTES - ack.encodeToByteArray().size))
        }
        assertEquals(HelloResult.Connected("Fake desktop"), result)
    }

    @Test
    fun closeWithoutReplyIsReported() {
        val result = sayHelloTo { close(CloseReason(CloseReason.Codes.NORMAL, "bye")) }
        assertEquals(HelloResult.Failed("server closed the connection"), result)
    }

    @Test
    fun tlsIsRefusedInsteadOfFallingBackToPlaintext() {
        // Nothing listens on this port: a connection attempt would report "could not connect".
        // The exact TLS message proves the client refused before trying plaintext.
        val freePort = ServerSocket(0).use { it.localPort }
        val result = runBlocking {
            connectOnce(ServerAddress("127.0.0.1", freePort), phone, TransportConfig.Tls("ab12"))
        }
        assertEquals(HelloResult.Failed("TLS is not implemented yet"), result)
    }

    @Test
    fun nothingListeningIsReported() {
        val freePort = ServerSocket(0).use { it.localPort }
        val result = runBlocking { connectOnce(ServerAddress("127.0.0.1", freePort), phone, TransportConfig.Plaintext) }
        val failed = assertIs<HelloResult.Failed>(result)
        assertTrue("could not connect" in failed.reason, failed.reason)
    }
}
