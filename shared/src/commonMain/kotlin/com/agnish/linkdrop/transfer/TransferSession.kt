package com.agnish.linkdrop.transfer

import com.agnish.linkdrop.protocol.Cancel
import com.agnish.linkdrop.protocol.Chunk
import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.ErrorCodes
import com.agnish.linkdrop.protocol.ErrorMessage
import com.agnish.linkdrop.protocol.FEATURE_TRANSFER
import com.agnish.linkdrop.protocol.FileBegin
import com.agnish.linkdrop.protocol.FileDecision
import com.agnish.linkdrop.protocol.FileEnd
import com.agnish.linkdrop.protocol.FileNames
import com.agnish.linkdrop.protocol.FileResult
import com.agnish.linkdrop.protocol.FrameChannel
import com.agnish.linkdrop.protocol.Hello
import com.agnish.linkdrop.protocol.HelloAck
import com.agnish.linkdrop.protocol.Message
import com.agnish.linkdrop.protocol.OfferedFile
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.TransferDone
import com.agnish.linkdrop.protocol.TransferErrors
import com.agnish.linkdrop.protocol.TransferLimits
import com.agnish.linkdrop.protocol.TransferOffer
import com.agnish.linkdrop.protocol.TransferResponse
import com.agnish.linkdrop.protocol.WireFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Everything after the handshake on one connection: answering stray or broken frames, and file
 * transfer in both directions (PROTOCOL.md, "File transfer").
 *
 * How it is organised:
 * - [run] starts four coroutines on one thread ([context]), so the state below needs no locks:
 *   the **reader** (handles every incoming frame), the **sender** (sends queued transfers one at a
 *   time), the **offer handler** (asks the user about incoming offers, one at a time) and the
 *   **command** loop (cancel requests from the UI).
 * - [send] and [cancel] can be called from any thread; they only put requests into channels.
 * - [transfers] is what the UI shows. It is updated atomically, so any thread may read it.
 */
class TransferSession(
    private val channel: FrameChannel,
    private val peerName: String,
    /** Both sides listed [FEATURE_TRANSFER]; see [agreed]. Without it only the basic rules apply. */
    private val transferEnabled: Boolean,
    /** Chunk size for sending: the smaller of both sides' `maxChunkBytes`. */
    private val sendChunkBytes: Int,
    /** Largest chunk accepted: this side's own `maxChunkBytes`. */
    private val receiveChunkBytes: Int,
    /** Where received files go; null if this side cannot receive. */
    private val sink: FileSink?,
    private val decider: OfferDecider,
    private val log: (String) -> Unit = {},
    private val context: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
) {
    init {
        require(sendChunkBytes > 0 && receiveChunkBytes > 0) { "chunk sizes must be positive" }
    }

    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()

    private val sendQueue = Channel<Outgoing>(Channel.UNLIMITED)
    private val cancelRequests = Channel<String>(Channel.UNLIMITED)
    private val offerQueue = Channel<TransferOffer>(Channel.UNLIMITED)

    // ---- State below is only touched by the coroutines started in run() (one thread). ----

    private val outgoingById = mutableMapOf<String, Outgoing>()
    private val cancelledBeforeStart = mutableSetOf<String>()
    private val incomingById = mutableMapOf<String, Incoming>()
    private val pendingOffers = mutableSetOf<String>()
    private val peerCancelledOffers = mutableSetOf<String>()
    private var open: OpenFile? = null

    /** After a failed or cancelled file, ignore its in-flight chunks and fileEnd until the next fileBegin. */
    private var discarding = false
    private var sendProgressMark = TimeSource.Monotonic.markNow()
    private var receiveProgressMark = TimeSource.Monotonic.markNow()

    // ---- Public API ----

    /** Queues [files] for sending. Returns the transfer's id (for [cancel]). */
    fun send(files: List<FileSource>): String {
        check(transferEnabled) { "the other device does not support file transfer" }
        require(files.isNotEmpty() && files.size <= TransferLimits.MAX_FILES_PER_OFFER) {
            "a transfer has 1 to ${TransferLimits.MAX_FILES_PER_OFFER} files"
        }
        val transfer = Outgoing(ProtocolCodec.newMessageId(), files)
        val progress = files.mapIndexed { i, f -> FileProgress(i, f.name, f.size, FileStatus.Waiting) }
        _transfers.update { it + TransferProgress(transfer.transferId, Direction.Outgoing, progress) }
        sendQueue.trySend(transfer)
        return transfer.transferId
    }

    /** Cancels a transfer in either direction. Does nothing if it already finished. */
    fun cancel(transferId: String) {
        cancelRequests.trySend(transferId)
    }

    /** Handles the connection until it ends. Anything unfinished then fails with "connection lost". */
    suspend fun run() {
        withContext(context) {
            try {
                coroutineScope {
                    val workers = listOf(
                        launch { senderLoop() },
                        launch { offerLoop() },
                        launch { commandLoop() },
                    )
                    readerLoop()
                    workers.forEach { it.cancel() }
                }
            } finally {
                withContext(NonCancellable) { endAll() }
            }
        }
    }

    // ---- Reader: every incoming frame ----

    private suspend fun readerLoop() {
        var malformed = 0
        while (true) {
            val frame = channel.incoming.receiveCatching().getOrNull() ?: break
            when (frame) {
                is WireFrame.Text -> when (val decoded = ProtocolCodec.decode(frame.text)) {
                    is Decoded.Ok -> onMessage(decoded.id, decoded.message)
                    is Decoded.UnsupportedType ->
                        sendMessage(ErrorMessage(ErrorCodes.UNSUPPORTED_TYPE, decoded.type, decoded.id))
                    is Decoded.UnsupportedVersion -> {
                        reject(ErrorMessage(ErrorCodes.VERSION_MISMATCH, ref = decoded.id))
                        break
                    }
                    is Decoded.Malformed -> {
                        val error = ErrorMessage(ErrorCodes.MALFORMED, decoded.reason)
                        if (++malformed < MAX_MALFORMED_FRAMES) {
                            sendMessage(error)
                        } else {
                            log("Closing: too many malformed frames from \"$peerName\"")
                            reject(error)
                            break
                        }
                    }
                }
                is WireFrame.Binary -> {
                    if (!transferEnabled) {
                        reject(ErrorMessage(ErrorCodes.BAD_REQUEST, "binary frame without transfer"))
                        break
                    }
                    onChunk(frame.bytes)
                }
            }
        }
    }

    private suspend fun onMessage(id: String, message: Message) {
        when (message) {
            is ErrorMessage -> onPeerError(message)
            is Hello, is HelloAck -> sendMessage(ErrorMessage(ErrorCodes.BAD_REQUEST, "unexpected message", id))
            else -> if (!transferEnabled) {
                sendMessage(ErrorMessage(ErrorCodes.BAD_REQUEST, "unexpected message", id))
            } else {
                when (message) {
                    is TransferOffer -> onOffer(id, message)
                    is TransferResponse -> outgoingById[message.transferId]?.replies?.send(Reply.Peer(message))
                    is FileResult -> outgoingById[message.transferId]?.replies?.send(Reply.Peer(message))
                    is Cancel -> onPeerCancel(message)
                    is FileBegin -> onFileBegin(message)
                    is FileEnd -> onFileEnd(message)
                    is TransferDone -> onTransferDone(message)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun onPeerError(error: ErrorMessage) {
        val target = error.ref?.let { ref -> outgoingById.values.firstOrNull { it.offerMessageId == ref } }
        if (target != null) target.replies.send(Reply.Peer(error)) else log("Error from \"$peerName\": ${error.code}")
    }

    private suspend fun onPeerCancel(cancel: Cancel) {
        // transferIds are random UUIDs, so one id never names transfers in both directions.
        val outgoing = outgoingById[cancel.transferId]
        when {
            outgoing != null -> outgoing.replies.send(Reply.Peer(cancel))
            cancel.transferId in incomingById -> cancelIncoming(cancel.transferId, cancel.fileId, byPeer = true)
            // An offer still waiting for the user. Unknown ids are ignored, so they cannot pile up.
            cancel.transferId in pendingOffers && cancel.fileId == null -> peerCancelledOffers += cancel.transferId
        }
    }

    // ---- Sender: one queued transfer at a time ----

    private class Outgoing(val transferId: String, val files: List<FileSource>) {
        var offerMessageId: String? = null
        val replies = Channel<Reply>(Channel.UNLIMITED)
        val skipped = mutableSetOf<Int>()
    }

    private sealed interface Reply {
        class Peer(val message: Message) : Reply
        data object LocalCancel : Reply
    }

    private enum class Next { NextFile, StopTransfer }

    private suspend fun senderLoop() {
        for (transfer in sendQueue) {
            val id = transfer.transferId
            if (id in cancelledBeforeStart) {
                markRemaining(id, FileStatus.Cancelled)
                continue
            }
            outgoingById[id] = transfer
            try {
                sendTransfer(transfer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markRemaining(id, FileStatus.Failed(describe(e)))
            } finally {
                outgoingById.remove(id)
            }
        }
    }

    private suspend fun sendTransfer(transfer: Outgoing) {
        val id = transfer.transferId
        val offer = TransferOffer(id, transfer.files.mapIndexed { i, f -> OfferedFile(i, f.name, f.size, f.mimeType) })
        val offerId = ProtocolCodec.newMessageId()
        transfer.offerMessageId = offerId
        sendMessage(offer, offerId)
        log("Offered ${transfer.files.size} file(s) to \"$peerName\"")

        var accepted: Set<Int>? = null
        while (accepted == null) {
            when (val reply = transfer.replies.receive()) {
                is Reply.LocalCancel -> {
                    sendMessage(Cancel(id))
                    markRemaining(id, FileStatus.Cancelled)
                    return
                }
                is Reply.Peer -> when (val message = reply.message) {
                    is TransferResponse -> accepted = message.files.filter { it.accept }.map { it.fileId }.toSet()
                    is ErrorMessage -> {
                        markRemaining(id, FileStatus.Declined, note = "refused: ${message.code}")
                        return
                    }
                    is Cancel -> if (message.fileId == null) {
                        markRemaining(id, FileStatus.Cancelled)
                        return
                    } else {
                        transfer.skipped += message.fileId
                    }
                    else -> Unit // a fileResult before any file was sent: ignore
                }
            }
        }

        for ((fileId, file) in transfer.files.withIndex()) {
            when {
                fileId !in accepted -> setStatus(id, fileId, FileStatus.Declined)
                fileId in transfer.skipped -> setStatus(id, fileId, FileStatus.Cancelled)
                sendFile(transfer, fileId, file) == Next.StopTransfer -> {
                    markRemaining(id, FileStatus.Cancelled)
                    return
                }
            }
        }
        sendMessage(TransferDone(id))
    }

    private suspend fun sendFile(transfer: Outgoing, fileId: Int, file: FileSource): Next {
        val id = transfer.transferId
        val reader = try {
            file.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus(id, fileId, FileStatus.Failed("could not open: ${describe(e)}"))
            return Next.NextFile
        }
        val sha = newSha256()
        var sent = 0L
        sendMessage(FileBegin(id, fileId, 0))
        setStatus(id, fileId, FileStatus.Transferring(0))
        try {
            val buffer = ByteArray(sendChunkBytes)
            while (true) {
                // The receiver may have failed or cancelled this file while we were sending.
                transfer.replies.tryReceive().getOrNull()?.let { reply ->
                    handleReplyWhileSending(transfer, fileId, reply)?.let { return it }
                }
                val n = try {
                    reader.read(buffer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Tell the receiver, so it does not wait for the rest of this file.
                    sendMessage(Cancel(id, fileId))
                    setStatus(id, fileId, FileStatus.Failed("could not read: ${describe(e)}"))
                    return Next.NextFile
                }
                if (n < 0) break
                if (n == 0) continue
                if (sent + n > file.size) return abandonChangedFile(id, fileId)
                sha.update(buffer, 0, n)
                channel.sendBinary(Chunk.encode(fileId, buffer, 0, n))
                sent += n
                reportSendProgress(id, fileId, sent, file.size)
            }
        } finally {
            reader.close()
        }
        if (sent != file.size) return abandonChangedFile(id, fileId)
        sendMessage(FileEnd(id, fileId, sha.hexDigest()))

        while (true) {
            val reply = transfer.replies.receive()
            handleReplyWhileSending(transfer, fileId, reply)?.let { return it }
        }
    }

    /** Returns what to do next, or null to keep going with the current file. */
    private suspend fun handleReplyWhileSending(transfer: Outgoing, fileId: Int, reply: Reply): Next? {
        val id = transfer.transferId
        return when (reply) {
            is Reply.LocalCancel -> {
                sendMessage(Cancel(id))
                setStatus(id, fileId, FileStatus.Cancelled)
                Next.StopTransfer
            }
            is Reply.Peer -> when (val message = reply.message) {
                is FileResult -> when {
                    message.fileId != fileId -> null
                    message.ok -> {
                        setStatus(id, fileId, FileStatus.Done())
                        Next.NextFile
                    }
                    else -> {
                        setStatus(id, fileId, FileStatus.Failed(message.error ?: "failed on the other device"))
                        Next.NextFile
                    }
                }
                is Cancel -> when (message.fileId) {
                    null -> {
                        setStatus(id, fileId, FileStatus.Cancelled)
                        Next.StopTransfer
                    }
                    fileId -> {
                        setStatus(id, fileId, FileStatus.Cancelled)
                        Next.NextFile
                    }
                    else -> {
                        transfer.skipped += message.fileId
                        null
                    }
                }
                else -> null
            }
        }
    }

    /** The file changed size while being sent; its declared size can no longer be met. */
    private suspend fun abandonChangedFile(transferId: String, fileId: Int): Next {
        sendMessage(Cancel(transferId, fileId))
        setStatus(transferId, fileId, FileStatus.Failed("the file changed while it was being sent"))
        return Next.NextFile
    }

    // ---- Receiver: offers ----

    private class Accepted(val name: String, val size: Long, val mimeType: String?)

    private class Incoming(val transferId: String, val accepted: MutableMap<Int, Accepted>) {
        val finished = mutableSetOf<Int>()
    }

    private class OpenFile(
        val transfer: Incoming,
        val fileId: Int,
        val size: Long,
        val writer: FileWriter,
    ) {
        val sha = newSha256()
        var received = 0L
    }

    private suspend fun onOffer(id: String, offer: TransferOffer) {
        val problem = validate(offer)
        if (problem != null) {
            log("Refused an offer from \"$peerName\": $problem")
            sendMessage(ErrorMessage(ErrorCodes.BAD_REQUEST, problem, id))
            return
        }
        pendingOffers += offer.transferId
        offerQueue.send(offer)
    }

    private fun validate(offer: TransferOffer): String? {
        val files = offer.files
        return when {
            files.isEmpty() -> "offer has no files"
            files.size > TransferLimits.MAX_FILES_PER_OFFER ->
                "offer has more than ${TransferLimits.MAX_FILES_PER_OFFER} files"
            files.any { it.fileId < 0 } -> "negative fileId"
            files.map { it.fileId }.toSet().size != files.size -> "repeated fileId"
            files.any { it.size < 0 } -> "negative size"
            offer.transferId.isBlank() || offer.transferId in incomingById || offer.transferId in pendingOffers ->
                "invalid transferId"
            else -> null
        }
    }

    private suspend fun offerLoop() {
        for (offer in offerQueue) {
            val id = offer.transferId
            val names = offer.files.associate { it.fileId to FileNames.clean(it.name) }
            val acceptable = offer.files.filter { names[it.fileId] != null }
            val progress = offer.files.map { file ->
                val name = names[file.fileId]
                FileProgress(
                    file.fileId,
                    name ?: "(unusable name)",
                    file.size,
                    if (name == null) FileStatus.Declined else FileStatus.Waiting,
                )
            }
            _transfers.update { it + TransferProgress(id, Direction.Incoming, progress) }
            log("\"$peerName\" offers ${offer.files.size} file(s)")

            val total = acceptable.fold(0L) { sum, f -> if (sum > Long.MAX_VALUE - f.size) Long.MAX_VALUE else sum + f.size }
            val free = sink?.freeBytes()
            var note: String? = null
            val accept = when {
                sink == null -> false.also { note = "this device cannot receive files" }
                acceptable.isEmpty() -> false.also { note = "no file had a usable name" }
                free != null && total > free - FREE_SPACE_MARGIN_BYTES -> false.also { note = "not enough free space" }
                id in peerCancelledOffers -> false
                else -> {
                    val question = IncomingOffer(id, peerName, progress.filter { it.status == FileStatus.Waiting })
                    withTimeoutOrNull(TransferLimits.DECISION_TIMEOUT_SECONDS.seconds) { decider.decide(question) }
                        ?: false.also { note = "no answer in time" }
                }
            }

            pendingOffers -= id
            if (id in peerCancelledOffers) {
                peerCancelledOffers -= id
                markRemaining(id, FileStatus.Cancelled)
                continue
            }
            sendMessage(TransferResponse(id, offer.files.map { FileDecision(it.fileId, accept && names[it.fileId] != null) }))
            if (accept) {
                val accepted = acceptable.associate { it.fileId to Accepted(names.getValue(it.fileId)!!, it.size, it.mimeType) }
                incomingById[id] = Incoming(id, accepted.toMutableMap())
            } else {
                markRemaining(id, FileStatus.Declined, note)
            }
        }
    }

    // ---- Receiver: file data ----

    private suspend fun onFileBegin(begin: FileBegin) {
        open?.let { fail(it, TransferErrors.IO_ERROR, "a new file started before this one ended") }
        discarding = false
        val transfer = incomingById[begin.transferId]
        val info = transfer?.accepted?.get(begin.fileId)
        val error = when {
            transfer == null -> TransferErrors.UNKNOWN_TRANSFER
            info == null -> TransferErrors.REJECTED
            begin.fileId in transfer.finished || begin.offset != 0L -> TransferErrors.IO_ERROR
            else -> null
        }
        if (error != null) {
            sendMessage(FileResult(begin.transferId, begin.fileId, ok = false, error = error))
            discarding = true
            return
        }
        transfer!!
        info!!
        val writer = try {
            sink!!.create(info.name, info.size, info.mimeType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            transfer.finished += begin.fileId
            setStatus(transfer.transferId, begin.fileId, FileStatus.Failed("could not create file: ${describe(e)}"))
            sendMessage(FileResult(begin.transferId, begin.fileId, ok = false, error = TransferErrors.IO_ERROR))
            discarding = true
            return
        }
        open = OpenFile(transfer, begin.fileId, info.size, writer)
        setStatus(transfer.transferId, begin.fileId, FileStatus.Transferring(0))
    }

    private suspend fun onChunk(frame: ByteArray) {
        val file = open ?: return // nothing open: a chunk still in flight after a failure or cancel
        val chunk = Chunk.decode(frame)
        when {
            chunk == null || chunk.fileId != file.fileId -> fail(file, TransferErrors.IO_ERROR, "invalid data from sender")
            chunk.payload.size > receiveChunkBytes -> fail(file, TransferErrors.IO_ERROR, "chunk too large")
            file.received + chunk.payload.size > file.size ->
                fail(file, TransferErrors.SIZE_MISMATCH, "more data than announced")
            else -> try {
                file.writer.write(chunk.payload)
                file.sha.update(chunk.payload)
                file.received += chunk.payload.size
                reportReceiveProgress(file.transfer.transferId, file.fileId, file.received, file.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(file, TransferErrors.IO_ERROR, "could not write: ${describe(e)}")
            }
        }
    }

    private suspend fun onFileEnd(end: FileEnd) {
        val file = open
        if (file == null || file.transfer.transferId != end.transferId || file.fileId != end.fileId) {
            if (!discarding) log("Ignored fileEnd for a file that is not open")
            return
        }
        when {
            file.received != file.size -> fail(file, TransferErrors.SIZE_MISMATCH, "less data than announced")
            file.sha.hexDigest() != end.sha256.lowercase() ->
                fail(file, TransferErrors.CHECKSUM_MISMATCH, "checksum does not match: file damaged in transit")
            else -> {
                open = null
                file.transfer.finished += file.fileId
                val savedAs = try {
                    file.writer.commit()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching { file.writer.abort() }
                    setStatus(end.transferId, end.fileId, FileStatus.Failed("could not save: ${describe(e)}"))
                    sendMessage(FileResult(end.transferId, end.fileId, ok = false, error = TransferErrors.IO_ERROR))
                    return
                }
                setStatus(end.transferId, end.fileId, FileStatus.Done(savedAs))
                sendMessage(FileResult(end.transferId, end.fileId, ok = true))
            }
        }
    }

    private suspend fun onTransferDone(done: TransferDone) {
        val transfer = incomingById.remove(done.transferId) ?: return
        open?.takeIf { it.transfer === transfer }?.let { fail(it, TransferErrors.IO_ERROR, "transfer ended mid-file") }
        markRemaining(done.transferId, FileStatus.Failed("the sender skipped this file"))
        log("Transfer from \"$peerName\" finished")
    }

    /** Fails the open file: deletes the partial data, tells the sender, ignores its remaining chunks. */
    private suspend fun fail(file: OpenFile, error: String, reason: String) {
        open = null
        discarding = true
        file.transfer.finished += file.fileId
        runCatching { file.writer.abort() }
        setStatus(file.transfer.transferId, file.fileId, FileStatus.Failed(reason))
        sendMessage(FileResult(file.transfer.transferId, file.fileId, ok = false, error = error))
    }

    /** Cancels an incoming transfer (or one file of it). No reply is sent for a peer's cancel. */
    private suspend fun cancelIncoming(transferId: String, fileId: Int?, byPeer: Boolean) {
        val transfer = incomingById[transferId] ?: return
        val current = open
        if (current != null && current.transfer === transfer && (fileId == null || fileId == current.fileId)) {
            open = null
            discarding = true
            runCatching { current.writer.abort() }
        }
        if (fileId == null) {
            incomingById.remove(transferId)
            markRemaining(transferId, FileStatus.Cancelled)
            if (!byPeer) sendMessage(Cancel(transferId))
        } else {
            transfer.accepted.remove(fileId)
            transfer.finished += fileId
            setStatus(transferId, fileId, FileStatus.Cancelled)
        }
    }

    // ---- Commands from the UI ----

    private suspend fun commandLoop() {
        for (transferId in cancelRequests) {
            val outgoing = outgoingById[transferId]
            when {
                outgoing != null -> outgoing.replies.send(Reply.LocalCancel)
                transferId in incomingById -> cancelIncoming(transferId, null, byPeer = false)
                else -> cancelledBeforeStart += transferId // queued, not started yet
            }
        }
    }

    // ---- Helpers ----

    private suspend fun sendMessage(message: Message, id: String = ProtocolCodec.newMessageId()) {
        channel.sendText(ProtocolCodec.encode(message, id))
    }

    private suspend fun reject(error: ErrorMessage) {
        runCatching { sendMessage(error) }
        channel.close(CLOSE_POLICY_VIOLATION, error.code)
    }

    private suspend fun endAll() {
        open?.let { runCatching { it.writer.abort() } }
        open = null
        _transfers.update { list ->
            list.map { t ->
                if (t.finished) t else t.copy(files = t.files.map { f ->
                    if (f.status.isFinal) f else f.copy(status = FileStatus.Failed("connection lost"))
                })
            }
        }
    }

    private fun setStatus(transferId: String, fileId: Int, status: FileStatus) {
        _transfers.update { list ->
            list.map { t ->
                if (t.transferId != transferId) t else t.copy(files = t.files.map { f ->
                    if (f.fileId == fileId) f.copy(status = status) else f
                })
            }
        }
    }

    /** Sets every file of [transferId] that has not finished yet to [status]. */
    private fun markRemaining(transferId: String, status: FileStatus, note: String? = null) {
        _transfers.update { list ->
            list.map { t ->
                if (t.transferId != transferId) t else t.copy(
                    note = note ?: t.note,
                    files = t.files.map { f -> if (f.status.isFinal) f else f.copy(status = status) },
                )
            }
        }
    }

    private fun reportSendProgress(transferId: String, fileId: Int, bytes: Long, size: Long) {
        if (bytes < size && sendProgressMark.elapsedNow() < PROGRESS_INTERVAL) return
        sendProgressMark = TimeSource.Monotonic.markNow()
        setStatus(transferId, fileId, FileStatus.Transferring(bytes))
    }

    private fun reportReceiveProgress(transferId: String, fileId: Int, bytes: Long, size: Long) {
        if (bytes < size && receiveProgressMark.elapsedNow() < PROGRESS_INTERVAL) return
        receiveProgressMark = TimeSource.Monotonic.markNow()
        setStatus(transferId, fileId, FileStatus.Transferring(bytes))
    }

    private fun describe(e: Exception): String = e.message ?: e::class.simpleName ?: "error"

    companion object {
        /** The session is closed on this many malformed frames (PROTOCOL.md, compatibility rule 4). */
        const val MAX_MALFORMED_FRAMES = 3

        /** Space kept free on the receiving device beyond the accepted files. */
        const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024

        private const val CLOSE_POLICY_VIOLATION = 1008
        private val PROGRESS_INTERVAL = 100.milliseconds

        /** Whether both sides can transfer files. */
        fun agreed(localFeatures: List<String>, peerFeatures: List<String>): Boolean =
            FEATURE_TRANSFER in localFeatures && FEATURE_TRANSFER in peerFeatures

        /** Chunk size for sending: the smaller of both sides' limits, at least 1. */
        fun sendChunkBytes(localMax: Int, peerMax: Int): Int = minOf(localMax, peerMax).coerceAtLeast(1)
    }
}
