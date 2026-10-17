package com.agnish.linkdrop.transfer

import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.FileNames
import com.agnish.linkdrop.protocol.FrameChannel
import com.agnish.linkdrop.protocol.Message
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.WireFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** One end of an in-memory connection. Closing either end ends both, after buffered frames are read. */
class MemoryChannel : FrameChannel {
    private val inbox = Channel<WireFrame>(Channel.UNLIMITED)
    lateinit var peer: MemoryChannel
    var closed = false
        private set

    override val incoming: ReceiveChannel<WireFrame> get() = inbox

    override suspend fun sendText(text: String) = deliver(WireFrame.Text(text))

    override suspend fun sendBinary(bytes: ByteArray) = deliver(WireFrame.Binary(bytes.copyOf()))

    private suspend fun deliver(frame: WireFrame) {
        check(!closed) { "connection closed" }
        peer.inbox.send(frame)
    }

    override fun close(code: Int, reason: String) {
        if (closed) return
        closed = true
        peer.closed = true
        inbox.close()
        peer.inbox.close()
    }

    /** Ends the connection abnormally, as a network error would: this end sees [cause]. */
    fun fail(cause: Throwable) {
        closed = true
        peer.closed = true
        inbox.close(cause)
        peer.inbox.close()
    }

    // Helpers for tests that play the peer by hand.

    suspend fun send(message: Message, id: String = ProtocolCodec.newMessageId()) = sendText(ProtocolCodec.encode(message, id))

    suspend fun receiveFrame(): WireFrame? = withTimeout(10.seconds) { inbox.receiveCatching().getOrNull() }

    /** Next text message, skipping binary frames. */
    suspend fun receiveMessage(): Message {
        while (true) {
            val frame = receiveFrame() ?: error("connection closed")
            if (frame is WireFrame.Text) return assertIs<Decoded.Ok>(ProtocolCodec.decode(frame.text)).message
        }
    }

    /** Next text message with its envelope id. */
    suspend fun receiveWithId(): Pair<String, Message> {
        while (true) {
            val frame = receiveFrame() ?: error("connection closed")
            if (frame is WireFrame.Text) {
                val ok = assertIs<Decoded.Ok>(ProtocolCodec.decode(frame.text))
                return ok.id to ok.message
            }
        }
    }

    companion object {
        fun pair(): Pair<MemoryChannel, MemoryChannel> {
            val a = MemoryChannel()
            val b = MemoryChannel()
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}

/** A file in memory. [gateAfterBytes]: reading pauses there until [gate] completes. */
class MemorySource(
    override val name: String,
    private val data: ByteArray,
    override val size: Long = data.size.toLong(),
    override val mimeType: String? = null,
    private val gateAfterBytes: Int? = null,
    val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    private val failAfterBytes: Int? = null,
) : FileSource {
    override suspend fun open(): FileReader = object : FileReader {
        private var position = 0

        override suspend fun read(buffer: ByteArray): Int {
            if (gateAfterBytes != null && position >= gateAfterBytes) gate.await()
            if (failAfterBytes != null && position >= failAfterBytes) error("disk error")
            if (position >= data.size) return -1
            var n = minOf(buffer.size, data.size - position)
            if (gateAfterBytes != null && position < gateAfterBytes) n = minOf(n, gateAfterBytes - position)
            if (failAfterBytes != null && position < failAfterBytes) n = minOf(n, failAfterBytes - position)
            data.copyInto(buffer, 0, position, position + n)
            position += n
            return n
        }

        override fun close() = Unit
    }
}

/** Received files in memory. Never overwrites: picks `name (1).ext` like a real sink must. */
class MemorySink(private val free: Long? = null) : FileSink {
    val saved = linkedMapOf<String, ByteArray>()
    private val writing = mutableSetOf<String>()
    var aborted = 0
        private set

    override fun freeBytes(): Long? = free

    override suspend fun create(name: String, size: Long, mimeType: String?): FileWriter {
        val finalName = FileNames.unique(name) { it in saved || it in writing }!!
        writing += finalName
        return object : FileWriter {
            private val buffer = mutableListOf<Byte>()

            override suspend fun write(bytes: ByteArray) {
                bytes.forEach { buffer += it }
            }

            override suspend fun commit(): String {
                writing -= finalName
                saved[finalName] = buffer.toByteArray()
                return finalName
            }

            override suspend fun abort() {
                writing -= finalName
                aborted++
            }
        }
    }
}

/** Records every offer it is asked about. */
class RecordingDecider(private val answer: suspend (IncomingOffer) -> Boolean) : OfferDecider {
    val asked = mutableListOf<IncomingOffer>()

    override suspend fun decide(offer: IncomingOffer): Boolean {
        asked += offer
        return answer(offer)
    }

    companion object {
        fun acceptAll() = RecordingDecider { true }
        fun declineAll() = RecordingDecider { false }
        fun neverAnswer() = RecordingDecider { awaitCancellation() }
    }
}

/** Waits in virtual time, so the limit is longer than the 120 s decision timeout but still instant. */
suspend fun TransferSession.awaitTransfer(transferId: String, condition: (TransferProgress) -> Boolean): TransferProgress =
    withTimeout(10.minutes) {
        transfers.mapNotNull { list -> list.firstOrNull { it.transferId == transferId } }.first(condition)
    }

suspend fun TransferSession.awaitFinished(transferId: String): TransferProgress = awaitTransfer(transferId) { it.finished }
