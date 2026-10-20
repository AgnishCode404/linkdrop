package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.FEATURE_TRANSFER
import com.agnish.linkdrop.protocol.FrameChannel
import com.agnish.linkdrop.protocol.Handshake
import com.agnish.linkdrop.protocol.Hello
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.ServerAddress
import com.agnish.linkdrop.protocol.WireFrame
import com.agnish.linkdrop.transfer.FileReader
import com.agnish.linkdrop.transfer.FileSink
import com.agnish.linkdrop.transfer.FileSource
import com.agnish.linkdrop.transfer.FileStatus
import com.agnish.linkdrop.transfer.FileWriter
import com.agnish.linkdrop.transfer.PartialFile
import com.agnish.linkdrop.transfer.OfferDecider
import com.agnish.linkdrop.transfer.TransferProgress
import com.agnish.linkdrop.transfer.TransferSession
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [PhoneConnection] over real OkHttp sockets to a local server running the real transfer engine
 * on the desktop side. Covers what B1's flow control and B3's engine look like together.
 */
class PhoneConnectionTest {

    private val phone = LocalDevice("phone-1", "Pixel", features = listOf(FEATURE_TRANSFER))
    private val phoneSink = BytesSink()
    private val desktopSink = BytesSink()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val desktopSession = CompletableDeferred<TransferSession>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server?.stop(0, 1_000)
    }

    /** Starts a fake desktop that accepts every offer. Returns its port. */
    private fun startDesktop(features: List<String> = listOf(FEATURE_TRANSFER)): Int {
        val desktop = LocalDevice("desk-1", "Arch", features = features)
        val started = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket(SESSION_PATH) {
                    val decoded = ProtocolCodec.decode((incoming.receive() as Frame.Text).readText()) as Decoded.Ok
                    val hello = decoded.message as Hello
                    send(Frame.Text(ProtocolCodec.encode(Handshake.respondTo(hello, decoded.id, desktop))))
                    val session = TransferSession(
                        TestKtorChannel(this), hello.deviceName, TransferSession.agreed(desktop.features, hello.features),
                        TransferSession.sendChunkBytes(desktop.maxChunkBytes, hello.maxChunkBytes), desktop.maxChunkBytes,
                        desktopSink, OfferDecider { true },
                    )
                    desktopSession.complete(session)
                    session.run()
                }
            }
        }.start(wait = false)
        server = started
        return runBlocking { started.engine.resolvedConnectors().first().port }
    }

    private fun connection() = PhoneConnection(phone, phoneSink, scope, allowPlaintext = true, tls = null, paired = null).also { connection ->
        // Accept offers the way a user tapping "Accept" would.
        scope.launch { connection.offers.collect { offers -> offers.forEach { it.answer.complete(true) } } }
    }

    private suspend fun PhoneConnection.awaitState(test: (PhoneConnection.State) -> Boolean) =
        withTimeout(30.seconds) { state.first(test) }

    private suspend fun TransferSession.awaitFinished(id: String): TransferProgress = withTimeout(60.seconds) {
        transfers.mapNotNull { list -> list.firstOrNull { it.transferId == id } }.first { it.finished }
    }

    @Test
    fun connectsAndTransfersBothWays() = runBlocking {
        val port = startDesktop()
        val connection = connection()
        connection.connect(ServerAddress("127.0.0.1", port))
        val connected = assertIs<PhoneConnection.State.Connected>(
            connection.awaitState { it is PhoneConnection.State.Connected },
        )
        assertEquals("Arch", connected.peerName)
        assertTrue(connected.transferEnabled)

        // Phone to desktop: 5 MB through OkHttp (exercises the send-buffer flow control).
        val up = Random(1).nextBytes(5 * 1024 * 1024)
        val upId = connected.session.send(listOf(BytesSource("up.bin", up)))
        assertEquals(FileStatus.Done(), connected.session.awaitFinished(upId).files.single().status)
        assertContentEquals(up, desktopSink.saved["up.bin"])

        // Desktop to phone.
        val down = Random(2).nextBytes(300_000)
        val downId = desktopSession.await().send(listOf(BytesSource("down.bin", down)))
        assertEquals(FileStatus.Done("down.bin"), connected.session.awaitFinished(downId).files.single().status)
        assertContentEquals(down, phoneSink.saved["down.bin"])
    }

    @Test
    fun disconnectingKeepsHistoryAndGivesNoError() = runBlocking {
        val port = startDesktop()
        val connection = connection()
        connection.connect(ServerAddress("127.0.0.1", port))
        val connected = connection.awaitState { it is PhoneConnection.State.Connected } as PhoneConnection.State.Connected
        val id = connected.session.send(listOf(BytesSource("a.txt", byteArrayOf(1, 2, 3))))
        connected.session.awaitFinished(id)

        connection.disconnect()
        assertEquals(PhoneConnection.State.Disconnected(null), connection.awaitState { it is PhoneConnection.State.Disconnected })
        assertEquals(listOf(id), connection.history.value.map { it.progress.transferId })
    }

    @Test
    fun desktopGoingAwayIsReported() = runBlocking {
        val port = startDesktop()
        val connection = connection()
        connection.connect(ServerAddress("127.0.0.1", port))
        connection.awaitState { it is PhoneConnection.State.Connected }

        server?.stop(0, 1_000)
        val state = connection.awaitState { it is PhoneConnection.State.Disconnected } as PhoneConnection.State.Disconnected
        // "connection lost", plus the cause when the socket ended abnormally.
        assertTrue(state.reason?.startsWith("connection lost") == true, "$state")
    }

    @Test
    fun oldDesktopWithoutTransferIsConnectedButCannotTransfer() = runBlocking {
        val port = startDesktop(features = emptyList())
        val connection = connection()
        connection.connect(ServerAddress("127.0.0.1", port))
        val connected = connection.awaitState { it is PhoneConnection.State.Connected } as PhoneConnection.State.Connected
        assertEquals(false, connected.transferEnabled)
    }

    @Test
    fun failedConnectGivesTheReason() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }
        val connection = connection()
        connection.connect(ServerAddress("127.0.0.1", freePort))
        val state = connection.awaitState { it is PhoneConnection.State.Disconnected && it.reason != null }
        assertTrue("could not connect" in (state as PhoneConnection.State.Disconnected).reason!!, "$state")
    }
}

/** The server side of the test connection (the desktop app has its own copy in desktopApp). */
private class TestKtorChannel(private val session: DefaultWebSocketSession) : FrameChannel {
    private val frames = Channel<WireFrame>(16)
    override val incoming: ReceiveChannel<WireFrame> get() = frames

    init {
        session.launch {
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> frames.send(WireFrame.Text(frame.readText()))
                        is Frame.Binary -> frames.send(WireFrame.Binary(frame.readBytes()))
                        else -> Unit
                    }
                }
            } finally {
                frames.close()
            }
        }
    }

    override suspend fun sendText(text: String) = session.send(Frame.Text(text))
    override suspend fun sendBinary(bytes: ByteArray) = session.send(Frame.Binary(true, bytes))
    override fun close(code: Int, reason: String) {
        session.launch { session.close(CloseReason(code.toShort(), reason)) }
    }
}

private class BytesSource(override val name: String, private val data: ByteArray) : FileSource {
    override val size: Long = data.size.toLong()
    override val mimeType: String? = null
    override suspend fun open(): FileReader = object : FileReader {
        private var position = 0
        override suspend fun read(buffer: ByteArray): Int {
            if (position >= data.size) return -1
            val n = minOf(buffer.size, data.size - position)
            data.copyInto(buffer, 0, position, position + n)
            position += n
            return n
        }
        override fun close() = Unit
    }
}

private class BytesSink : FileSink {
    val saved = ConcurrentHashMap<String, ByteArray>()
    override fun freeBytes(): Long? = null
    override suspend fun create(name: String, size: Long, mimeType: String?): FileWriter = object : FileWriter {
        private val out = ByteArrayOutputStream()
        override suspend fun write(bytes: ByteArray) = out.write(bytes)
        override suspend fun commit(): String {
            saved[name] = out.toByteArray()
            return name
        }
        override suspend fun abort() = Unit
        override suspend fun keep(peer: String, bytes: Long) = Unit
    }
    override suspend fun claimPartial(peer: String, name: String, size: Long): PartialFile? = null
}
