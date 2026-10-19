package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.CHUNK_HEADER_BYTES
import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.FEATURE_TRANSFER
import com.agnish.linkdrop.protocol.FileDecision
import com.agnish.linkdrop.protocol.FileEnd
import com.agnish.linkdrop.protocol.FileResult
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.TransferOffer
import com.agnish.linkdrop.protocol.TransferResponse
import com.agnish.linkdrop.protocol.TransportConfig
import com.agnish.linkdrop.transfer.FileStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The real desktop server (real Netty, as started by the app) sending to a phone whose storage
 * stalls for 12 s mid-file. Before the fix, the desktop buffered the whole file in memory and
 * Netty's 10 s write timeout dropped the connection (seen on phones at 250-350 MB).
 * Takes about 15 s.
 */
class SlowPhoneTest {

    private val dir = createTempDirectory("linkdrop-slow")
    private val connected = CompletableDeferred<PeerConnection>()
    private val log = mutableListOf<String>()
    private val server = HelloServer(
        LocalDevice("desk", "Arch", features = listOf(FEATURE_TRANSFER)),
        port = 0,
        TransportConfig.Plaintext,
        ServerHooks(onConnected = { connected.complete(it) }, approver = { _, _, _ -> Approval.Allowed }),
    ) { synchronized(log) { log += it } }

    @AfterTest
    fun tearDown() {
        server.stop()
        dir.toFile().deleteRecursively()
    }

    /** A fake phone (JDK WebSocket client) that accepts offers and stops reading for a while. */
    private class SlowPhone(port: Int, private val stallAfterBytes: Long, private val stallMillis: Long) : WebSocket.Listener {
        val received = AtomicLong()
        val stalled = CompletableFuture<Unit>()
        val resumed = CompletableFuture<Unit>()
        val fileEnd = CompletableFuture<FileEnd>()
        val closed = CompletableFuture<String>()
        val ws: WebSocket = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI("ws://127.0.0.1:$port$SESSION_PATH"), this).join()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            when (val message = (ProtocolCodec.decode(data.toString()) as? Decoded.Ok)?.message) {
                is TransferOffer -> webSocket.sendText(
                    ProtocolCodec.encode(TransferResponse(message.transferId, message.files.map { FileDecision(it.fileId, true) })),
                    true,
                )
                is FileEnd -> fileEnd.complete(message)
                else -> Unit
            }
            webSocket.request(1)
            return null
        }

        /** True when the next binary data starts a new frame (whose first 4 bytes are the header). */
        private var atFrameStart = true

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            // Count file bytes only, not the 4-byte chunk header (a frame may arrive in pieces).
            val header = if (atFrameStart) CHUNK_HEADER_BYTES else 0
            atFrameStart = last
            val total = received.addAndGet((data.remaining() - header).toLong())
            if (!stalled.isDone && total >= stallAfterBytes) {
                stalled.complete(Unit)
                Thread {
                    Thread.sleep(stallMillis)
                    resumed.complete(Unit)
                    webSocket.request(1)
                }.start()
            } else {
                webSocket.request(1)
            }
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closed.complete("$statusCode $reason")
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closed.complete("error: $error")
        }
    }

    @Test
    fun aStallLongerThanTenSecondsNeitherDropsTheConnectionNorFillsMemory() = runBlocking {
        server.start()
        val size = 48L * 1024 * 1024
        val file = dir.resolve("big.bin").also { RandomAccessFile(it.toFile(), "rw").use { f -> f.setLength(size) } }
        val phone = SlowPhone(server.boundPort(), stallAfterBytes = 8L * 1024 * 1024, stallMillis = 12_000)
        phone.ws.sendText(ProtocolCodec.encode(LocalDevice("phone", "Pixel", features = listOf(FEATURE_TRANSFER)).hello()), true).join()
        val connection = withTimeout(10.seconds) { connected.await() }
        val id = connection.session.send(listOf(PathSource(file)))

        phone.stalled.get()
        // While the phone is not reading, the desktop must stop sending instead of buffering the
        // rest of the file in memory: it may only run a few MB ahead (socket buffers).
        var largestGap = 0L
        while (!phone.resumed.isDone) {
            val sent = (connection.session.transfers.value.single().files.single().status as? FileStatus.Transferring)?.bytes ?: 0L
            largestGap = maxOf(largestGap, sent - phone.received.get())
            Thread.sleep(250)
        }
        assertTrue(largestGap < 16L * 1024 * 1024, "desktop ran ${largestGap / (1024 * 1024)} MB ahead of the phone")
        assertFalse(phone.closed.isDone, "connection dropped during the stall: ${phone.closed.getNow("")}")

        // After the stall the whole file arrives and the connection is still usable.
        val end = phone.fileEnd.get(30, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(size, phone.received.get())
        phone.ws.sendText(ProtocolCodec.encode(FileResult(end.transferId, end.fileId, ok = true)), true).join()
        val finished = withTimeout(10.seconds) {
            connection.session.transfers.mapNotNull { list -> list.firstOrNull { it.transferId == id } }.first { it.finished }
        }
        assertEquals(FileStatus.Done(), finished.files.single().status)
    }
}
