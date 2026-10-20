package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.ErrorCodes
import com.agnish.linkdrop.protocol.ErrorMessage
import com.agnish.linkdrop.protocol.FEATURE_TRANSFER
import com.agnish.linkdrop.protocol.Fingerprints
import com.agnish.linkdrop.protocol.Hello
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.PairedDevice
import com.agnish.linkdrop.protocol.Pairing
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.ServerAddress
import com.agnish.linkdrop.transfer.FileSink
import com.agnish.linkdrop.transfer.FileWriter
import com.agnish.linkdrop.transfer.PartialFile
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The phone's real connection code over real TLS against a fake desktop. Only the Keystore and
 * Android's exporter are replaced (by a software key and the JDK 25 exporter); on the phone the
 * same flow uses [PhoneIdentity] and [AndroidKeyingMaterialExporter].
 */
class PhonePairingTest {

    private val dir: Path = createTempDirectory("linkdrop-pairing")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val phone = LocalDevice("phone-1", "Pixel", features = listOf(FEATURE_TRANSFER))
    private val phoneIdentity = keytoolIdentity("phone")
    private val desktopIdentity = keytoolIdentity("desktop")
    private var stored: String? = null
    private val paired = PairedDesktops(load = { stored }, save = { stored = it })
    private val desktop = FakeDesktop(desktopIdentity).also { it.start() }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        desktop.stop()
        dir.toFile().deleteRecursively()
    }

    private fun keytoolIdentity(name: String): ClientIdentity {
        val file = dir.resolve("$name.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=$name",
            "-validity", "7300", "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD,
        ).redirectErrorStream(true).start()
        check(process.waitFor() == 0) { process.inputStream.readBytes().decodeToString() }
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, PASSWORD.toCharArray()) } }
        return ClientIdentity(store.getKey(name, PASSWORD.toCharArray()) as PrivateKey, store.getCertificate(name) as X509Certificate)
    }

    private fun connection() = PhoneConnection(
        phone, NoSink, scope, allowPlaintext = false,
        tls = PhoneTls(identity = { phoneIdentity }, exporter = JdkExporter),
        paired = paired,
    )

    /** Every state the connection goes through, in order. */
    private fun PhoneConnection.recordStates(): List<PhoneConnection.State> =
        CopyOnWriteArrayList<PhoneConnection.State>().also { states -> scope.launch { state.collect { states += it } } }

    private suspend inline fun <reified T : PhoneConnection.State> PhoneConnection.await(): T =
        withTimeout(20.seconds) { state.first { it is T } } as T

    private val address get() = ServerAddress("127.0.0.1", desktop.port)

    @Test
    fun aNewDesktopIsPairedAfterBothUsersSeeTheSameCode() = runBlocking {
        val connection = connection()
        connection.connect(address)
        val confirm = connection.await<PhoneConnection.State.ConfirmPairing>()
        // The connection is still open here, so give a too-early hello time to arrive.
        Thread.sleep(500)
        assertTrue(desktop.hellos.isEmpty(), "no hello before the phone's user confirmed")

        confirm.confirm()
        val waiting = connection.await<PhoneConnection.State.WaitingForDesktop>()
        assertEquals(confirm.code, waiting.code)

        val connected = connection.await<PhoneConnection.State.Connected>()
        assertEquals(desktop.codesSeen.single(), confirm.code, "phone and desktop must show the same code")
        assertTrue(connected.secure)
        val remembered = paired.byDeviceId("desk-1")
        assertEquals(Fingerprints.of(desktopIdentity.certificate.encoded), remembered?.certSha256)
        assertEquals(Fingerprints.of(phoneIdentity.certificate.encoded), desktop.clientFingerprints.single())
    }

    @Test
    fun cancellingAtTheCodeSendsNoHelloAndRemembersNothing() = runBlocking {
        val connection = connection()
        connection.connect(address)
        connection.await<PhoneConnection.State.ConfirmPairing>().cancel()

        assertEquals("pairing cancelled", connection.await<PhoneConnection.State.Disconnected>().reason)
        desktop.awaitSessionEnded()
        assertTrue(desktop.hellos.isEmpty())
        assertTrue(paired.devices.value.isEmpty())
    }

    @Test
    fun aPairedDesktopConnectsWithoutAskingPinnedByItsId() = runBlocking {
        paired.add(PairedDevice("desk-1", "Arch", Fingerprints.of(desktopIdentity.certificate.encoded), 1))
        val connection = connection()
        val states = connection.recordStates()
        connection.connect(address, desktopId = "desk-1")

        assertTrue(connection.await<PhoneConnection.State.Connected>().secure)
        assertTrue(states.none { it is PhoneConnection.State.ConfirmPairing }, "a paired desktop needs no code: $states")
    }

    @Test
    fun aPairedDesktopReachedByAddressAloneNeedsNoPairing() = runBlocking {
        paired.add(PairedDevice("desk-1", "Arch", Fingerprints.of(desktopIdentity.certificate.encoded), 1))
        val connection = connection()
        val states = connection.recordStates()
        connection.connect(address) // typed address, desktop id unknown

        connection.await<PhoneConnection.State.Connected>()
        assertTrue(states.none { it is PhoneConnection.State.ConfirmPairing }, "$states")
    }

    @Test
    fun aChangedCertificateIsRefusedBeforeAnyMessage() = runBlocking {
        // Paired with a certificate the desktop no longer has (reset, or someone else at this address).
        paired.add(PairedDevice("desk-1", "Arch", "0".repeat(64), 1))
        val connection = connection()
        connection.connect(address, desktopId = "desk-1")

        val reason = connection.await<PhoneConnection.State.Disconnected>().reason.orEmpty()
        assertTrue("identity has changed" in reason, reason)
        assertTrue(desktop.hellos.isEmpty(), "nothing may be sent to an unverified desktop")
    }

    @Test
    fun theDesktopDeclining() = runBlocking {
        desktop.answer = "decline"
        val connection = connection()
        connection.connect(address)
        connection.await<PhoneConnection.State.ConfirmPairing>().confirm()

        assertEquals("the desktop did not allow the connection", connection.await<PhoneConnection.State.Disconnected>().reason)
        assertTrue(paired.devices.value.isEmpty(), "a declined pairing is not remembered")
    }

    @Test
    fun theDesktopBusyWithAnotherPhone() = runBlocking {
        desktop.answer = "busy"
        val connection = connection()
        connection.connect(address)
        connection.await<PhoneConnection.State.ConfirmPairing>().confirm()

        val reason = connection.await<PhoneConnection.State.Disconnected>().reason.orEmpty()
        assertTrue("busy" in reason, reason)
    }

    @Test
    fun aKnownCertificateAnsweringWithAnotherDeviceIdIsRefused() = runBlocking {
        paired.add(PairedDevice("desk-1", "Arch", Fingerprints.of(desktopIdentity.certificate.encoded), 1))
        desktop.ackDeviceId = "someone-else"
        val connection = connection()
        connection.connect(address, desktopId = "desk-1")

        val reason = connection.await<PhoneConnection.State.Disconnected>().reason.orEmpty()
        assertTrue("does not match" in reason, reason)
    }

    @Test
    fun disconnectingWhileTheCodeIsShownCancels() = runBlocking {
        val connection = connection()
        connection.connect(address)
        connection.await<PhoneConnection.State.ConfirmPairing>()
        connection.disconnect()

        assertNull(connection.await<PhoneConnection.State.Disconnected>().reason)
        desktop.awaitSessionEnded()
        assertTrue(desktop.hellos.isEmpty())
    }
}

/** keytool requires at least 6 characters. */
private const val PASSWORD = "linkdrop-test"

/** The JDK 25 TLS exporter (stands in for Android's SSLSockets in JVM tests). */
private object JdkExporter : KeyingMaterialExporter {
    override fun export(socket: SSLSocket): ByteArray = exportFrom(socket.session)
}

/** Reflection: the method is new in JDK 25 and missing from the Android API these tests compile against. */
private fun exportFrom(session: SSLSession): ByteArray =
    ExtendedSSLSession::class.java
        .getMethod("exportKeyingMaterialData", String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
        .invoke(session, Pairing.EXPORTER_LABEL, Pairing.EXPORTER_CONTEXT, Pairing.EXPORTER_LENGTH) as ByteArray

/**
 * A desktop for tests: mutual TLS like the real one, then answers hello as [answer] says
 * ("allow" sends helloAck, "decline" / "busy" the matching error).
 */
private class FakeDesktop(private val identity: ClientIdentity) {
    @Volatile var answer = "allow"
    @Volatile var ackDeviceId = "desk-1"
    val hellos = CopyOnWriteArrayList<Hello>()
    val codesSeen = CopyOnWriteArrayList<String>()
    val clientFingerprints = CopyOnWriteArrayList<String>()

    /** WebSocket sessions that ended (however): "nothing was sent" can only be judged after that. */
    val sessionsEnded = java.util.concurrent.atomic.AtomicInteger()
    private val codes = ConcurrentHashMap<String, String>()
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    var port = 0
        private set

    fun start() {
        val anyClient = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tls = SslContextBuilder.forServer(identity.privateKey, identity.certificate)
            .trustManager(anyClient).clientAuth(ClientAuth.REQUIRE).protocols("TLSv1.3").sslProvider(SslProvider.JDK).build()
        server = embeddedServer(Netty, configure = {
            connector { port = 0; host = "127.0.0.1" }
            channelPipelineConfig = {
                val handler = tls.newHandler(channel().alloc())
                addFirst("tls", handler)
                val remote = channel().remoteAddress() as InetSocketAddress
                handler.handshakeFuture().addListener { done ->
                    if (!done.isSuccess) return@addListener
                    val session = handler.engine().session
                    codes["${remote.address.hostAddress}:${remote.port}"] = Pairing.code(exportFrom(session))
                    clientFingerprints += Fingerprints.of((session.peerCertificates[0] as X509Certificate).encoded)
                }
            }
        }) {
            install(WebSockets)
            routing {
                webSocket(SESSION_PATH) {
                    try {
                        handle()
                    } finally {
                        sessionsEnded.incrementAndGet()
                    }
                }
            }
        }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handle() {
        val decoded = ProtocolCodec.decode((incoming.receive() as Frame.Text).readText()) as Decoded.Ok
        val hello = decoded.message as Hello
        hellos += hello
        val origin = call.request.origin
        // The code the real desktop would show in its dialog for this connection.
        codes["${origin.remoteAddress}:${origin.remotePort}"]?.let { codesSeen += it }
        when (answer) {
            "decline" -> {
                send(Frame.Text(ProtocolCodec.encode(ErrorMessage(ErrorCodes.NOT_ALLOWED))))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not_allowed"))
            }
            "busy" -> {
                send(Frame.Text(ProtocolCodec.encode(ErrorMessage(ErrorCodes.BUSY))))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "busy"))
            }
            else -> {
                send(Frame.Text(ProtocolCodec.encode(LocalDevice(ackDeviceId, "Arch", features = listOf(FEATURE_TRANSFER)).helloAck())))
                for (frame in incoming) {
                    // keep the connection open until the phone closes it
                }
            }
        }
    }

    fun stop() = server.stop(0, 1_000)

    fun awaitSessionEnded() {
        repeat(400) {
            if (sessionsEnded.get() > 0) return
            Thread.sleep(25)
        }
        error("the desktop never saw the connection end")
    }
}

private object NoSink : FileSink {
    override fun freeBytes(): Long? = null
    override suspend fun create(name: String, size: Long, mimeType: String?): FileWriter = error("unused")
    override suspend fun claimPartial(peer: String, name: String, size: Long): PartialFile? = null
}
