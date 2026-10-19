package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.ErrorCodes
import com.agnish.linkdrop.protocol.ErrorMessage
import com.agnish.linkdrop.protocol.Fingerprints
import com.agnish.linkdrop.protocol.Handshake
import com.agnish.linkdrop.protocol.Hello
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.MAX_FRAME_BYTES
import com.agnish.linkdrop.protocol.Message
import com.agnish.linkdrop.protocol.PairedDevice
import com.agnish.linkdrop.protocol.Pairing
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.TransportConfig
import com.agnish.linkdrop.transfer.FileSink
import com.agnish.linkdrop.transfer.OfferDecider
import com.agnish.linkdrop.transfer.TransferSession
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
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
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.seconds

/** How long a paired phone has to send `hello`. An unpaired one has [Pairing.CONFIRM_TIMEOUT_SECONDS]. */
private val HELLO_TIMEOUT = 10.seconds

/** Longer than the ping timeout below, so pings (not this) decide when a phone is gone. */
const val RESPONSE_WRITE_TIMEOUT_SECONDS = 60

/** What the server does with a phone once the handshake succeeded. */
class ServerHooks(
    /** Where received files go; null means this server cannot receive. */
    val sink: FileSink? = null,
    val decider: OfferDecider = OfferDecider { false },
    val onConnected: (PeerConnection) -> Unit = {},
    val onDisconnected: (PeerConnection) -> Unit = {},
    /** Asks the user about phones that are not paired. The default refuses them all. */
    val approver: Approver = Approver { _, _, _ -> Approval.Declined },
    /** Paired phones (TLS only). Null: nothing is remembered and every phone is asked about. */
    val pairedPhones: PairedPhones? = null,
)

/** What TLS established about one connection, before any WebSocket message. */
class PeerTls(val certSha256: String, val pairingCode: String)

/** The desktop's WebSocket server: TLS, the hello handshake and approval, then file transfer. */
class HelloServer(
    private val local: LocalDevice,
    private val port: Int,
    private val transport: TransportConfig,
    private val hooks: ServerHooks,
    /** Required for [TransportConfig.Tls]: the certificate phones pin. */
    private val identity: DesktopIdentity? = null,
    private val log: (String) -> Unit,
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** TLS details per connection, by the phone's "address:port", for the WebSocket code to look up. */
    private val tlsPeers = ConcurrentHashMap<String, PeerTls>()

    /** Starts listening on all interfaces. Throws if the port is in use or TLS lacks an identity. */
    fun start() {
        val tls: SslContext? = when (transport) {
            TransportConfig.Plaintext -> null
            is TransportConfig.Tls -> serverTlsContext(checkNotNull(identity) { "TLS needs this desktop's identity" })
        }
        server = embeddedServer(
            Netty,
            configure = {
                connector {
                    this.port = this@HelloServer.port
                    host = "0.0.0.0"
                }
                // Netty's default is 10 s: any write still waiting after 10 s kills the connection,
                // which happens whenever the phone falls 10 s behind (e.g. busy storage). Dead
                // phones are detected by the WebSocket ping timeout instead (see linkDropModule).
                responseWriteTimeoutSeconds = RESPONSE_WRITE_TIMEOUT_SECONDS
                if (tls != null) channelPipelineConfig = { addTls(tls) }
            },
        ) {
            linkDropModule(local, hooks, tlsLookup = if (tls != null) ::lookupTls else null, log = log)
        }.start(wait = false)
    }

    /** TLS in front of everything else on the connection (installed per connection by Netty). */
    private fun io.netty.channel.ChannelPipeline.addTls(context: SslContext) {
        val channel = channel()
        val handler = context.newHandler(channel.alloc())
        addFirst("tls", handler)
        val key = (channel.remoteAddress() as? InetSocketAddress)?.let { "${it.address.hostAddress}:${it.port}" } ?: return
        handler.handshakeFuture().addListener { done ->
            if (!done.isSuccess) return@addListener
            val session = handler.engine().session as ExtendedSSLSession
            val certificate = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@addListener
            val exported = session.exportKeyingMaterialData(Pairing.EXPORTER_LABEL, Pairing.EXPORTER_CONTEXT, Pairing.EXPORTER_LENGTH)
            tlsPeers[key] = PeerTls(Fingerprints.of(certificate.encoded), Pairing.code(exported))
        }
        channel.closeFuture().addListener { tlsPeers.remove(key) }
    }

    private fun lookupTls(address: String, port: Int): PeerTls? = tlsPeers["$address:$port"]

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }

    /** The port actually listened on (differs from [port] when that is 0, as in tests). */
    suspend fun boundPort(): Int = checkNotNull(server) { "not started" }.engine.resolvedConnectors().first().port
}

/**
 * TLS 1.3 with this desktop's certificate, requiring a certificate from the phone. Any phone
 * certificate is accepted here; whether the phone is trusted is decided afterwards by its
 * fingerprint. TLS itself proves the phone holds the private key of the certificate it shows.
 */
private fun serverTlsContext(identity: DesktopIdentity): SslContext =
    SslContextBuilder.forServer(identity.privateKey, identity.certificate)
        .trustManager(AnyClientCertificate)
        .clientAuth(ClientAuth.REQUIRE)
        .protocols("TLSv1.3")
        .sslProvider(SslProvider.JDK) // the JDK provider supports the TLS exporter used for pairing codes
        .build()

private object AnyClientCertificate : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("no client certificate")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("not used by a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Installs WebSockets and the session route. Separate from [HelloServer] so tests can use it.
 * [tlsLookup] finds a connection's TLS details; null means plaintext (development only).
 */
fun Application.linkDropModule(
    local: LocalDevice,
    hooks: ServerHooks = ServerHooks(),
    tlsLookup: ((address: String, port: Int) -> PeerTls?)? = null,
    log: (String) -> Unit,
) {
    install(WebSockets) {
        // A ping every 15 s; the phone must answer within 30 s or the connection is closed.
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = MAX_FRAME_BYTES.toLong()
    }
    routing {
        webSocket(SESSION_PATH) {
            handleSession(local, hooks, tlsLookup, log)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleSession(
    local: LocalDevice,
    hooks: ServerHooks,
    tlsLookup: ((String, Int) -> PeerTls?)?,
    log: (String) -> Unit,
) {
    val origin = call.request.origin
    val peer = origin.remoteAddress
    log("Connection from $peer")

    val tls = tlsLookup?.invoke(origin.remoteAddress, origin.remotePort)
    if (tlsLookup != null && tls == null) {
        log("Rejected $peer: no TLS details")
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no TLS"))
        return
    }

    val hello = handshake(local, peer, tls, hooks, log) ?: return
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
    var reason: String? = null
    try {
        reason = session.run()
    } finally {
        hooks.onDisconnected(connection)
        log("Disconnected: \"${hello.deviceName}\" ($peer)" + (reason?.let { ": $it" } ?: ""))
    }
}

/**
 * Waits for the phone's hello, checks it, and decides whether the phone may connect: a paired
 * phone (same certificate and device id) at once, any other only if the user allows it.
 * Returns the hello, or null if the session was closed.
 */
private suspend fun DefaultWebSocketServerSession.handshake(
    local: LocalDevice,
    peer: String,
    tls: PeerTls?,
    hooks: ServerHooks,
    log: (String) -> Unit,
): Hello? {
    // An unpaired phone's user is comparing the pairing code before the phone sends hello.
    val knownCertificate = tls != null && hooks.pairedPhones?.devices?.value?.any { it.certSha256 == tls.certSha256 } == true
    val helloTimeout = if (tls == null || knownCertificate) HELLO_TIMEOUT else Pairing.CONFIRM_TIMEOUT_SECONDS.seconds
    val first = withTimeoutOrNull(helloTimeout) { incoming.receiveCatching().getOrNull() }
    if (first !is Frame.Text) {
        log("Rejected $peer: no hello")
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "expected hello"))
        return null
    }

    val decoded = ProtocolCodec.decode(first.readText())
    val error: ErrorMessage = when (decoded) {
        is Decoded.Ok -> {
            val hello = decoded.message as? Hello
            if (hello == null) {
                ErrorMessage(ErrorCodes.BAD_REQUEST, "expected hello", decoded.id)
            } else {
                when (val reply = Handshake.respondTo(hello, decoded.id, local)) {
                    is ErrorMessage -> reply
                    else -> {
                        if (!allowed(hello, peer, tls, hooks, log)) return null
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

/** Paired phones pass; any other phone needs the user's Allow. Rejects (and closes) otherwise. */
private suspend fun DefaultWebSocketServerSession.allowed(
    hello: Hello,
    peer: String,
    tls: PeerTls?,
    hooks: ServerHooks,
    log: (String) -> Unit,
): Boolean {
    val paired = tls?.let { hooks.pairedPhones?.trusted(hello.deviceId, it.certSha256) }
    if (paired != null) {
        hooks.pairedPhones?.rename(paired.certSha256, hello.deviceName)
        return true
    }

    log("\"${hello.deviceName}\" ($peer) asks to connect")
    when (askUser(hello.deviceName, peer, tls?.pairingCode, hooks.approver)) {
        Approval.Allowed -> {
            if (tls != null) {
                hooks.pairedPhones?.add(PairedDevice(hello.deviceId, hello.deviceName, tls.certSha256, Instant.now().epochSecond))
                log("Paired with \"${hello.deviceName}\"")
            }
            return true
        }
        Approval.Busy -> {
            log("Rejected \"${hello.deviceName}\" ($peer): another phone is waiting for approval")
            reject(ErrorMessage(ErrorCodes.BUSY, "another device is waiting for approval"))
        }
        Approval.Declined, null -> {
            log("Declined \"${hello.deviceName}\" ($peer)")
            reject(ErrorMessage(ErrorCodes.NOT_ALLOWED, "the desktop's user did not allow this connection"))
        }
    }
    return false
}

/** Asks the user; returns null (and closes the dialog) if the phone disconnects meanwhile. */
private suspend fun DefaultWebSocketServerSession.askUser(
    deviceName: String,
    peer: String,
    code: String?,
    approver: Approver,
): Approval? = coroutineScope {
    val answer = async { approver.approve(deviceName, peer, code) }
    val gone = async { runCatching { closeReason.await() } }
    select {
        answer.onAwait { gone.cancel(); it }
        gone.onAwait { answer.cancel(); null }
    }
}

private suspend fun DefaultWebSocketServerSession.sendMessage(message: Message) {
    send(Frame.Text(ProtocolCodec.encode(message)))
}

private suspend fun DefaultWebSocketServerSession.reject(error: ErrorMessage) {
    runCatching { sendMessage(error) }
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, error.code))
}
