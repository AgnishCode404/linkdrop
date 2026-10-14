package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.DEFAULT_MAX_CHUNK_BYTES
import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.ErrorCodes
import com.agnish.linkdrop.protocol.ErrorMessage
import com.agnish.linkdrop.protocol.Handshake
import com.agnish.linkdrop.protocol.Hello
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.Message
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Largest WebSocket frame accepted: one binary chunk plus its 4-byte header (see PROTOCOL.md). */
const val MAX_FRAME_BYTES = DEFAULT_MAX_CHUNK_BYTES + 4

private val HELLO_TIMEOUT = 10.seconds

/** The session is closed on this many malformed frames (PROTOCOL.md, compatibility rule 4). */
const val MAX_MALFORMED_FRAMES = 3

/** The desktop's WebSocket server. Slice A: plaintext `ws://`, answers the hello handshake. */
class HelloServer(
    private val local: LocalDevice,
    private val port: Int,
    private val log: (String) -> Unit,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** Starts listening on all interfaces. Throws if the port is already in use. */
    fun start() {
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            linkDropModule(local, log)
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }
}

/** Installs WebSockets and the session route. Separate from [HelloServer] so tests can use it. */
fun Application.linkDropModule(local: LocalDevice, log: (String) -> Unit) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = MAX_FRAME_BYTES.toLong()
    }
    routing {
        webSocket(SESSION_PATH) {
            handleSession(local, log)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleSession(local: LocalDevice, log: (String) -> Unit) {
    val peer = call.request.origin.remoteAddress
    log("Connection from $peer")

    val peerName = handshake(local, peer, log) ?: return
    log("Hello from \"$peerName\" ($peer)")

    // Slice A has no messages after the handshake; answer anything that arrives per PROTOCOL.md.
    var malformedFrames = 0
    for (frame in incoming) {
        when (frame) {
            is Frame.Text -> when (val decoded = ProtocolCodec.decode(frame.readText())) {
                is Decoded.Ok -> when (val message = decoded.message) {
                    is ErrorMessage -> log("Error from \"$peerName\": ${message.code}")
                    else -> sendMessage(ErrorMessage(ErrorCodes.BAD_REQUEST, "unexpected message", decoded.id))
                }
                is Decoded.UnsupportedType ->
                    sendMessage(ErrorMessage(ErrorCodes.UNSUPPORTED_TYPE, decoded.type, decoded.id))
                is Decoded.UnsupportedVersion -> {
                    reject(ErrorMessage(ErrorCodes.VERSION_MISMATCH, ref = decoded.id))
                    break
                }
                is Decoded.Malformed -> {
                    val error = ErrorMessage(ErrorCodes.MALFORMED, decoded.reason)
                    if (++malformedFrames < MAX_MALFORMED_FRAMES) {
                        sendMessage(error)
                    } else {
                        log("Closing \"$peerName\": too many malformed frames")
                        reject(error)
                        break
                    }
                }
            }
            // No "transfer" feature yet, so binary frames are a protocol error.
            is Frame.Binary -> {
                reject(ErrorMessage(ErrorCodes.BAD_REQUEST, "binary frame without transfer"))
                break
            }
            else -> Unit
        }
    }
    log("Disconnected: \"$peerName\" ($peer)")
}

/** Waits for the peer's hello and replies. Returns the peer's name, or null if the session was closed. */
private suspend fun DefaultWebSocketServerSession.handshake(
    local: LocalDevice,
    peer: String,
    log: (String) -> Unit,
): String? {
    val first = withTimeoutOrNull(HELLO_TIMEOUT) { incoming.receiveCatching().getOrNull() }
    if (first !is Frame.Text) {
        log("Rejected $peer: no hello")
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "expected hello"))
        return null
    }

    val error = when (val decoded = ProtocolCodec.decode(first.readText())) {
        is Decoded.Ok -> {
            val hello = decoded.message as? Hello
            if (hello == null) {
                ErrorMessage(ErrorCodes.BAD_REQUEST, "expected hello", decoded.id)
            } else {
                when (val reply = Handshake.respondTo(hello, decoded.id, local)) {
                    is ErrorMessage -> reply
                    else -> {
                        sendMessage(reply)
                        return hello.deviceName
                    }
                }
            }
        }
        is Decoded.UnsupportedVersion -> ErrorMessage(ErrorCodes.VERSION_MISMATCH, ref = decoded.id)
        is Decoded.UnsupportedType -> ErrorMessage(ErrorCodes.BAD_REQUEST, "expected hello", decoded.id)
        is Decoded.Malformed -> ErrorMessage(ErrorCodes.MALFORMED, decoded.reason)
    }
    log("Rejected $peer: ${error.code}")
    reject(error)
    return null
}

private suspend fun DefaultWebSocketServerSession.sendMessage(message: Message) {
    send(Frame.Text(ProtocolCodec.encode(message)))
}

private suspend fun DefaultWebSocketServerSession.reject(error: ErrorMessage) {
    sendMessage(error)
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, error.code))
}
