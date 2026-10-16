package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.ErrorCodes
import com.agnish.linkdrop.protocol.ErrorMessage
import com.agnish.linkdrop.protocol.Handshake
import com.agnish.linkdrop.protocol.Hello
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.MAX_FRAME_BYTES
import com.agnish.linkdrop.protocol.Message
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.TransportConfig
import com.agnish.linkdrop.transfer.FileSink
import com.agnish.linkdrop.transfer.OfferDecider
import com.agnish.linkdrop.transfer.TransferSession
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

private val HELLO_TIMEOUT = 10.seconds

/** What the server does with a phone once the handshake succeeded. */
class ServerHooks(
    /** Where received files go; null means this server cannot receive. */
    val sink: FileSink? = null,
    val decider: OfferDecider = OfferDecider { false },
    val onConnected: (PeerConnection) -> Unit = {},
    val onDisconnected: (PeerConnection) -> Unit = {},
)

/** The desktop's WebSocket server: the hello handshake, then file transfer with the phone. */
class HelloServer(
    private val local: LocalDevice,
    private val port: Int,
    private val transport: TransportConfig,
    private val hooks: ServerHooks,
    private val log: (String) -> Unit,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** Starts listening on all interfaces. Throws if the port is already in use or TLS is requested. */
    fun start() {
        // Never fall back: a TLS config must not turn into a plaintext server.
        check(transport is TransportConfig.Plaintext) { "TLS is not implemented yet" }
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            linkDropModule(local, hooks, log)
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }
}

/** Installs WebSockets and the session route. Separate from [HelloServer] so tests can use it. */
fun Application.linkDropModule(local: LocalDevice, hooks: ServerHooks = ServerHooks(), log: (String) -> Unit) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = MAX_FRAME_BYTES.toLong()
    }
    routing {
        webSocket(SESSION_PATH) {
            handleSession(local, hooks, log)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleSession(
    local: LocalDevice,
    hooks: ServerHooks,
    log: (String) -> Unit,
) {
    val peer = call.request.origin.remoteAddress
    log("Connection from $peer")

    val hello = handshake(local, peer, log) ?: return
    log("Hello from \"${hello.deviceName}\" ($peer)")

    // From here on, the shared engine handles every frame (including broken ones, per PROTOCOL.md).
    val transferEnabled = TransferSession.agreed(local.features, hello.features)
    val session = TransferSession(
        channel = KtorFrameChannel(this),
        peerName = hello.deviceName,
        transferEnabled = transferEnabled,
        sendChunkBytes = TransferSession.sendChunkBytes(local.maxChunkBytes, hello.maxChunkBytes),
        receiveChunkBytes = local.maxChunkBytes,
        sink = hooks.sink,
        decider = hooks.decider,
        log = log,
    )
    val connection = PeerConnection(hello.deviceName, peer, session, transferEnabled)
    hooks.onConnected(connection)
    try {
        session.run()
    } finally {
        hooks.onDisconnected(connection)
        log("Disconnected: \"${hello.deviceName}\" ($peer)")
    }
}

/** Waits for the peer's hello and replies. Returns the hello, or null if the session was closed. */
private suspend fun DefaultWebSocketServerSession.handshake(
    local: LocalDevice,
    peer: String,
    log: (String) -> Unit,
): Hello? {
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
                        return hello
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
