package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.ErrorCodes
import com.agnish.linkdrop.protocol.ErrorMessage
import com.agnish.linkdrop.protocol.FEATURE_TRANSFER
import com.agnish.linkdrop.protocol.Fingerprints
import com.agnish.linkdrop.protocol.HelloAck
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.Message
import com.agnish.linkdrop.protocol.Pairing
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.TransportConfig
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Collections
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The real desktop server with TLS, pairing and approval, against a test phone that speaks TLS
 * and WebSocket directly (so it can present any certificate, read its pairing code, or vanish).
 */
class DesktopTlsTest {

    private val root: Path = createTempDirectory("linkdrop-tls")
    private val desktopIdentity = DesktopIdentity.loadOrCreate(root.resolve("desktop"))
    private val paired = PairedPhones(root.resolve("desktop").resolve(PairedPhones.FILE_NAME))
    private val state = DesktopState(approvalTimeout = 2.seconds)
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = HelloServer(
        LocalDevice("desk-1", "Arch", features = listOf(FEATURE_TRANSFER)),
        port = 0,
        TransportConfig.Tls(desktopIdentity.fingerprint),
        ServerHooks(approver = state.approver, pairedPhones = paired),
        desktopIdentity,
    ) { log += it }.also { it.start() }
    private val port = runBlocking { server.boundPort() }
    private val phones = mutableListOf<TestPhone>()

    // Phone identities are just a key and a certificate, like the desktop's.
    private val phoneA = DesktopIdentity.loadOrCreate(root.resolve("phone-a"))
    private val phoneB = DesktopIdentity.loadOrCreate(root.resolve("phone-b"))

    @AfterTest
    fun tearDown() {
        phones.forEach { it.close() }
        server.stop()
        root.toFile().deleteRecursively()
    }

    private fun connect(identity: DesktopIdentity?) = TestPhone(port, identity).also { phones += it }

    private fun TestPhone.hello(deviceId: String = "phone-a", name: String = "Pixel") =
        sendText(ProtocolCodec.encode(LocalDevice(deviceId, name, features = listOf(FEATURE_TRANSFER)).hello()))

    /** Waits for the "wants to connect" dialog. */
    private fun awaitRequest(): ConnectionRequest {
        repeat(200) {
            state.request.value?.let { return it }
            Thread.sleep(25)
        }
        error("no connection request appeared; log: $log")
    }

    private fun pairA(): TestPhone {
        val phone = connect(phoneA)
        phone.hello()
        awaitRequest().allow()
        assertIs<HelloAck>(phone.receiveMessage())
        return phone
    }

    @Test
    fun theDesktopPresentsItsOwnIdentity() {
        assertEquals(desktopIdentity.fingerprint, connect(phoneA).desktopFingerprint)
    }

    @Test
    fun unpairedPhoneIsAskedWithTheSameCodeAndRememberedAfterAllow() {
        val phone = connect(phoneA)
        phone.hello(name = "Pixel")
        val request = awaitRequest()
        assertEquals("Pixel", request.deviceName)
        assertEquals(phone.code, request.code, "both screens must show the same pairing code")

        request.allow()
        assertEquals("Arch", assertIs<HelloAck>(phone.receiveMessage()).deviceName)
        assertNotNull(paired.trusted("phone-a", phoneA.fingerprint))
        assertNull(state.request.value)

        // Next time: no dialog, an immediate helloAck.
        phone.close()
        val again = connect(phoneA)
        again.hello()
        assertIs<HelloAck>(again.receiveMessage())
        assertNull(state.request.value)
    }

    @Test
    fun declineMeansNotAllowedAndNothingIsRemembered() {
        val phone = connect(phoneA)
        phone.hello()
        awaitRequest().decline()
        assertEquals(ErrorCodes.NOT_ALLOWED, assertIs<ErrorMessage>(phone.receiveMessage()).code)
        assertNull(phone.receiveMessage(), "the connection must be closed")
        assertNull(paired.trusted("phone-a", phoneA.fingerprint))
    }

    @Test
    fun noAnswerInTimeMeansNotAllowed() {
        val phone = connect(phoneA)
        phone.hello()
        awaitRequest() // and nobody answers (the timeout is 2 s in this test)
        assertEquals(ErrorCodes.NOT_ALLOWED, assertIs<ErrorMessage>(phone.receiveMessage()).code)
        assertNull(state.request.value)
    }

    @Test
    fun aSecondUnpairedPhoneIsBusyWhileOneIsWaiting() {
        val first = connect(phoneA)
        first.hello(deviceId = "phone-a")
        val request = awaitRequest()

        val second = connect(phoneB)
        second.hello(deviceId = "phone-b", name = "Stranger")
        assertEquals(ErrorCodes.BUSY, assertIs<ErrorMessage>(second.receiveMessage()).code)
        assertNull(second.receiveMessage())
        assertEquals("Pixel", state.request.value?.deviceName, "the first dialog stays")

        request.allow()
        assertIs<HelloAck>(first.receiveMessage())
    }

    @Test
    fun aPairedPhoneIsNotBusyWhileAnotherIsWaiting() {
        pairA().close()
        val stranger = connect(phoneB)
        stranger.hello(deviceId = "phone-b")
        awaitRequest()

        val known = connect(phoneA)
        known.hello(deviceId = "phone-a")
        assertIs<HelloAck>(known.receiveMessage())
    }

    @Test
    fun aPairedCertificateWithAnotherDeviceIdIsAskedAgain() {
        pairA().close()
        val phone = connect(phoneA)
        phone.hello(deviceId = "someone-else")
        assertEquals(phone.code, awaitRequest().code)
    }

    @Test
    fun aPairedDeviceIdWithAnotherCertificateIsAskedAgain() {
        pairA().close()
        val impostor = connect(phoneB)
        impostor.hello(deviceId = "phone-a", name = "Pixel")
        assertEquals(impostor.code, awaitRequest().code)
    }

    @Test
    fun forgetMeansAskingAgain() {
        pairA().close()
        paired.forget(phoneA.fingerprint)
        val phone = connect(phoneA)
        phone.hello()
        awaitRequest()
    }

    @Test
    fun aPhoneThatLeavesTakesItsDialogWithIt() {
        val phone = connect(phoneA)
        phone.hello()
        awaitRequest()
        phone.close()
        repeat(200) {
            if (state.request.value == null) return
            Thread.sleep(25)
        }
        error("the dialog stayed after the phone left")
    }

    @Test
    fun aConnectionWithoutAClientCertificateIsRefused() {
        assertFailsWith<IOException> {
            val phone = connect(null)
            phone.hello()
            phone.receiveMessage() ?: throw EOFException("closed")
        }
        assertNull(state.request.value, "no dialog for a phone without a certificate")
    }

    @Test
    fun everySessionHasItsOwnPairingCode() {
        // Two sessions (as a man-in-the-middle would have) almost surely differ: 1 in a million.
        assertNotEquals(connect(phoneA).code, connect(phoneA).code)
    }

    @Test
    fun aPairedPhonesNewNameIsRemembered() {
        pairA().close()
        val phone = connect(phoneA)
        phone.hello(name = "Pixel 9")
        assertIs<HelloAck>(phone.receiveMessage())
        assertEquals("Pixel 9", paired.trusted("phone-a", phoneA.fingerprint)?.name)
    }
}

/** A phone for tests: TLS with an optional client certificate, then a minimal WebSocket client. */
private class TestPhone(port: Int, identity: DesktopIdentity?) : AutoCloseable {
    val socket: SSLSocket
    val desktopFingerprint: String
    val code: String

    init {
        val password = "test".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            if (identity != null) setKeyEntry("phone", identity.privateKey, password, arrayOf(identity.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }.keyManagers
        var seen: X509Certificate? = null
        val trust = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { seen = chain[0] }
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLSv1.3").apply { init(keyManagers, arrayOf(trust), SecureRandom()) }
        socket = context.socketFactory.createSocket(Socket("127.0.0.1", port), "127.0.0.1", port, true) as SSLSocket
        socket.soTimeout = 15_000
        socket.startHandshake()
        desktopFingerprint = Fingerprints.of(seen!!.encoded)
        val exported = (socket.session as ExtendedSSLSession)
            .exportKeyingMaterialData(Pairing.EXPORTER_LABEL, Pairing.EXPORTER_CONTEXT, Pairing.EXPORTER_LENGTH)
        code = Pairing.code(exported)
        upgrade(port)
    }

    private fun upgrade(port: Int) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        socket.outputStream.write(
            ("GET $SESSION_PATH HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
        )
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = socket.inputStream.read()
            if (b < 0) throw EOFException("closed during upgrade")
            head.append(b.toChar())
        }
        if (!head.startsWith("HTTP/1.1 101")) throw IOException("upgrade refused: $head")
    }

    /** Clients must mask their frames (RFC 6455). */
    fun sendText(text: String) = sendFrame(0x1, text.toByteArray())

    private fun sendFrame(opcode: Int, data: ByteArray) {
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val header = when {
            data.size < 126 -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or data.size).toByte())
            else -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or 126).toByte(), (data.size shr 8).toByte(), data.size.toByte())
        }
        socket.outputStream.write(header + mask + ByteArray(data.size) { (data[it].toInt() xor mask[it % 4].toInt()).toByte() })
        socket.outputStream.flush()
    }

    /** The next control message, or null once the desktop closed the connection. */
    fun receiveMessage(): Message? {
        while (true) {
            val (opcode, payload) = receiveFrame() ?: return null
            when (opcode) {
                0x1 -> return assertIs<Decoded.Ok>(ProtocolCodec.decode(payload.decodeToString())).message
                0x8 -> return null
                0x9 -> sendFrame(0xA, payload) // answer pings
                else -> Unit
            }
        }
    }

    private fun receiveFrame(): Pair<Int, ByteArray>? = try {
        val input = socket.inputStream
        val b0 = input.read()
        if (b0 < 0) null else {
            var length = input.read() and 0x7f
            if (length == 126) length = (input.read() shl 8) or input.read()
            (b0 and 0x0f) to input.readNBytes(length)
        }
    } catch (e: javax.net.ssl.SSLException) {
        null // the desktop ended the TLS session
    }

    override fun close() = runCatching { socket.close() }.let { }
}
