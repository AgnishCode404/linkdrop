package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.FEATURE_TRANSFER
import com.agnish.linkdrop.protocol.HelloAck
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.transfer.FileStatus
import com.agnish.linkdrop.transfer.OfferDecider
import com.agnish.linkdrop.transfer.TransferProgress
import com.agnish.linkdrop.transfer.TransferSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The real desktop server (Ktor WebSockets, [FolderSink], [DesktopState]) against a phone-side
 * [TransferSession] over Ktor's in-memory test client. Files go both ways through temp folders.
 */
class DesktopTransferTest {

    private val root: Path = createTempDirectory("linkdrop-e2e")
    private val desktopDir = root.resolve("desktop")
    private val phoneDir = root.resolve("phone")
    private val desktop = LocalDevice("desk-1", "Arch", features = listOf(FEATURE_TRANSFER))
    private val phone = LocalDevice("phone-1", "Pixel", features = listOf(FEATURE_TRANSFER))

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private suspend fun TransferSession.awaitFinished(id: String): TransferProgress = withTimeout(30.seconds) {
        transfers.mapNotNull { list -> list.firstOrNull { it.transferId == id } }.first { it.finished }
    }

    private fun file(name: String, size: Int, seed: Int): Path =
        root.resolve(name).apply { writeBytes(Random(seed).nextBytes(size)) }

    /** Connects a phone, runs [block] with both sides' sessions, then disconnects. */
    private fun withPhone(
        phoneAccepts: Boolean = true,
        desktopAnswers: suspend (PendingOffer) -> Unit = { it.answer.complete(true) },
        block: suspend (phoneSession: TransferSession, desktopConnection: PeerConnection, state: DesktopState) -> Unit,
    ) = testApplication {
        val state = DesktopState()
        val connected = CompletableDeferred<PeerConnection>()
        val hooks = ServerHooks(
            sink = FolderSink(desktopDir),
            decider = state.decider,
            onConnected = { state.connected(it); connected.complete(it) },
            onDisconnected = state::disconnected,
            approver = { _, _, _ -> Approval.Allowed },
        )
        application { linkDropModule(desktop, hooks) {} }
        val client = createClient { install(WebSockets) }

        client.webSocket(SESSION_PATH) {
            send(Frame.Text(ProtocolCodec.encode(phone.hello())))
            val ack = assertIs<HelloAck>(
                assertIs<Decoded.Ok>(ProtocolCodec.decode((incoming.receive() as Frame.Text).readText())).message,
            )
            assertEquals(listOf(FEATURE_TRANSFER), ack.features)

            val phoneSession = TransferSession(
                channel = KtorFrameChannel(this),
                peerName = ack.deviceName,
                transferEnabled = TransferSession.agreed(phone.features, ack.features),
                sendChunkBytes = TransferSession.sendChunkBytes(phone.maxChunkBytes, ack.maxChunkBytes),
                receiveChunkBytes = phone.maxChunkBytes,
                sink = FolderSink(phoneDir),
                decider = OfferDecider { phoneAccepts },
            )
            val running = launch { phoneSession.run() }
            // Answer desktop offers the way a user clicking in the window would.
            val answering = launch { state.offers.collect { offers -> offers.forEach { desktopAnswers(it) } } }

            block(phoneSession, connected.await(), state)

            answering.cancel()
            running.cancel()
        }
    }

    @Test
    fun phoneSendsAQueueToTheDesktop() = withPhone { phoneSession, _, _ ->
        val a = file("a.bin", 300_000, seed = 1)
        val b = file("b.txt", 10, seed = 2)
        val id = phoneSession.send(listOf(PathSource(a), PathSource(b)))

        val sent = phoneSession.awaitFinished(id)
        assertEquals(listOf(FileStatus.Done(), FileStatus.Done()), sent.files.map { it.status })
        assertContentEquals(a.readBytes(), desktopDir.resolve("a.bin").readBytes())
        assertContentEquals(b.readBytes(), desktopDir.resolve("b.txt").readBytes())
    }

    @Test
    fun desktopSendsToThePhone() = withPhone { phoneSession, connection, _ ->
        val c = file("c.bin", 200_000, seed = 3)
        val id = connection.session.send(listOf(PathSource(c)))

        assertEquals(FileStatus.Done(), connection.session.awaitFinished(id).files.single().status)
        assertEquals(FileStatus.Done("c.bin"), phoneSession.awaitFinished(id).files.single().status)
        assertContentEquals(c.readBytes(), phoneDir.resolve("c.bin").readBytes())
    }

    @Test
    fun declinedOnTheDesktopSavesNothing() = withPhone(desktopAnswers = { it.answer.complete(false) }) { phoneSession, _, state ->
        val id = phoneSession.send(listOf(PathSource(file("no.txt", 10, seed = 4))))

        assertEquals(FileStatus.Declined, phoneSession.awaitFinished(id).files.single().status)
        assertEquals(false, desktopDir.resolve("no.txt").toFile().exists())
        assertEquals(emptyList(), state.offers.value)
    }

    @Test
    fun connectionIsListedWhileConnected() = withPhone { _, connection, state ->
        assertEquals("Pixel", connection.name)
        assertEquals(true, connection.transferEnabled)
        assertEquals(listOf(connection), state.connections.value)
    }
}
