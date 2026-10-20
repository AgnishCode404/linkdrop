package com.agnish.linkdrop.transfer

import com.agnish.linkdrop.protocol.Cancel
import com.agnish.linkdrop.protocol.Chunk
import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.DEFAULT_MAX_CHUNK_BYTES
import com.agnish.linkdrop.protocol.ErrorCodes
import com.agnish.linkdrop.protocol.ErrorMessage
import com.agnish.linkdrop.protocol.FileBegin
import com.agnish.linkdrop.protocol.FileDecision
import com.agnish.linkdrop.protocol.FileEnd
import com.agnish.linkdrop.protocol.FileResult
import com.agnish.linkdrop.protocol.OfferedFile
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.TransferErrors
import com.agnish.linkdrop.protocol.TransferOffer
import com.agnish.linkdrop.protocol.TransferResponse
import com.agnish.linkdrop.protocol.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One real session against a peer played by the test, which can break the rules. */
class TransferRulesTest {

    private class Setup(val session: TransferSession, val peer: MemoryChannel, val sink: MemorySink)

    private fun CoroutineScope.start(
        transferEnabled: Boolean = true,
        decider: OfferDecider = RecordingDecider.acceptAll(),
        sink: MemorySink = MemorySink(),
        /** The peer's certificate fingerprint: set when it is paired over TLS. */
        peerKey: String? = null,
    ): Setup {
        val (mine, theirs) = MemoryChannel.pair()
        val session = TransferSession(
            mine, "Rogue", transferEnabled,
            sendChunkBytes = DEFAULT_MAX_CHUNK_BYTES, receiveChunkBytes = DEFAULT_MAX_CHUNK_BYTES,
            sink = sink, decider = decider, peerKey = peerKey, context = EmptyCoroutineContext,
        )
        launch { session.run() }
        return Setup(session, theirs, sink)
    }

    private val emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private fun sha(data: ByteArray) = newSha256().apply { update(data) }.hexDigest()

    /** Offers one file and waits until it is accepted. */
    private suspend fun Setup.offerAccepted(size: Long, name: String = "f.bin") {
        peer.send(TransferOffer("t1", listOf(OfferedFile(0, name, size))))
        val response = assertIs<TransferResponse>(peer.receiveMessage())
        assertEquals(listOf(FileDecision(0, accept = true)), response.files)
    }

    @Test
    fun sha256MatchesKnownValues() {
        assertEquals(emptySha, sha(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha("abc".encodeToByteArray()))
    }

    @Test
    fun wrongChecksumIsRejectedAndNotSaved() = runTest {
        val s = backgroundScope.start()
        s.offerAccepted(3)
        s.peer.send(FileBegin("t1", 0))
        s.peer.sendBinary(Chunk.encode(0, byteArrayOf(1, 2, 3)))
        s.peer.send(FileEnd("t1", 0, sha(byteArrayOf(9, 9, 9))))

        assertEquals(FileResult("t1", 0, ok = false, error = TransferErrors.CHECKSUM_MISMATCH), s.peer.receiveMessage())
        assertTrue(s.sink.saved.isEmpty())
        assertEquals(1, s.sink.aborted)
    }

    @Test
    fun correctChecksumIsAccepted() = runTest {
        val s = backgroundScope.start()
        s.offerAccepted(3)
        s.peer.send(FileBegin("t1", 0))
        s.peer.sendBinary(Chunk.encode(0, byteArrayOf(1, 2, 3)))
        s.peer.send(FileEnd("t1", 0, sha(byteArrayOf(1, 2, 3)).uppercase())) // hex case does not matter

        assertEquals(FileResult("t1", 0, ok = true), s.peer.receiveMessage())
        assertContentEquals(byteArrayOf(1, 2, 3), s.sink.saved["f.bin"])
    }

    @Test
    fun moreDataThanAnnouncedFailsImmediately() = runTest {
        val s = backgroundScope.start()
        s.offerAccepted(3)
        s.peer.send(FileBegin("t1", 0))
        s.peer.sendBinary(Chunk.encode(0, byteArrayOf(1, 2, 3, 4)))

        // Answered before any fileEnd.
        assertEquals(FileResult("t1", 0, ok = false, error = TransferErrors.SIZE_MISMATCH), s.peer.receiveMessage())
        // Its remaining chunks and fileEnd are ignored; nothing else is sent.
        s.peer.sendBinary(Chunk.encode(0, byteArrayOf(5)))
        s.peer.send(FileEnd("t1", 0, emptySha))
        s.peer.send(TransferOffer("t2", listOf(OfferedFile(0, "next.txt", 1))))
        assertIs<TransferResponse>(s.peer.receiveMessage())
        assertTrue(s.sink.saved.isEmpty())
    }

    @Test
    fun lessDataThanAnnouncedFailsAtFileEnd() = runTest {
        val s = backgroundScope.start()
        s.offerAccepted(5)
        s.peer.send(FileBegin("t1", 0))
        s.peer.sendBinary(Chunk.encode(0, byteArrayOf(1, 2, 3)))
        s.peer.send(FileEnd("t1", 0, sha(byteArrayOf(1, 2, 3))))

        assertEquals(FileResult("t1", 0, ok = false, error = TransferErrors.SIZE_MISMATCH), s.peer.receiveMessage())
        assertTrue(s.sink.saved.isEmpty())
    }

    @Test
    fun chunkForAnotherFileIsAnError() = runTest {
        val s = backgroundScope.start()
        s.offerAccepted(3)
        s.peer.send(FileBegin("t1", 0))
        s.peer.sendBinary(Chunk.encode(7, byteArrayOf(1)))

        assertEquals(FileResult("t1", 0, ok = false, error = TransferErrors.IO_ERROR), s.peer.receiveMessage())
        assertEquals(1, s.sink.aborted)
    }

    @Test
    fun oversizedChunkIsAnError() = runTest {
        val s = backgroundScope.start()
        s.offerAccepted(DEFAULT_MAX_CHUNK_BYTES + 10L)
        s.peer.send(FileBegin("t1", 0))
        s.peer.sendBinary(Chunk.encode(0, ByteArray(DEFAULT_MAX_CHUNK_BYTES + 1)))

        assertEquals(FileResult("t1", 0, ok = false, error = TransferErrors.IO_ERROR), s.peer.receiveMessage())
    }

    @Test
    fun fileBeginForUnknownTransferOrUnacceptedFileIsRefused() = runTest {
        val s = backgroundScope.start()
        s.peer.send(FileBegin("nope", 0))
        assertEquals(FileResult("nope", 0, ok = false, error = TransferErrors.UNKNOWN_TRANSFER), s.peer.receiveMessage())

        s.offerAccepted(3)
        s.peer.send(FileBegin("t1", 5))
        assertEquals(FileResult("t1", 5, ok = false, error = TransferErrors.REJECTED), s.peer.receiveMessage())
    }

    @Test
    fun offsetWithoutAKeptPartIsRefused() = runTest {
        val s = backgroundScope.start()
        s.offerAccepted(3)
        s.peer.send(FileBegin("t1", 0, offset = 1))
        assertEquals(FileResult("t1", 0, ok = false, error = TransferErrors.IO_ERROR), s.peer.receiveMessage())
    }

    // ---- Resuming (PROTOCOL.md, "Resuming") ----

    private val key = "a".repeat(64)
    private val five = byteArrayOf(1, 2, 3, 4, 5)

    /** A sink holding the first two bytes of `f.bin` (5 bytes) from [peer]. */
    private fun sinkWithKeptPart(peer: String = key, name: String = "f.bin", size: Long = 5) = MemorySink().apply {
        kept += MemorySink.Kept(name, peer, 2, five.copyOf(2), size)
    }

    /** Offers `f.bin` (5 bytes) and returns the receiver's `resumeOffset` for it. */
    private suspend fun Setup.offerFiveBytes(): Long {
        peer.send(TransferOffer("t1", listOf(OfferedFile(0, "f.bin", 5))))
        val decision = assertIs<TransferResponse>(peer.receiveMessage()).files.single()
        assertTrue(decision.accept)
        return decision.resumeOffset
    }

    @Test
    fun keptPartIsResumedAndCompleted() = runTest {
        val s = backgroundScope.start(sink = sinkWithKeptPart(), peerKey = key)
        assertEquals(2, s.offerFiveBytes())

        s.peer.send(FileBegin("t1", 0, offset = 2))
        s.peer.sendBinary(Chunk.encode(0, byteArrayOf(3, 4, 5)))
        s.peer.send(FileEnd("t1", 0, sha(five)))

        assertEquals(FileResult("t1", 0, ok = true), s.peer.receiveMessage())
        assertContentEquals(five, s.sink.saved["f.bin"])
        assertTrue(s.sink.kept.isEmpty())
    }

    @Test
    fun resumedFileWithAWrongChecksumIsDeletedCompletely() = runTest {
        val s = backgroundScope.start(sink = sinkWithKeptPart(), peerKey = key)
        assertEquals(2, s.offerFiveBytes())

        // A different file with the same name and size: the whole-file checksum does not match.
        s.peer.send(FileBegin("t1", 0, offset = 2))
        s.peer.sendBinary(Chunk.encode(0, byteArrayOf(3, 4, 5)))
        s.peer.send(FileEnd("t1", 0, sha(byteArrayOf(9, 9, 3, 4, 5))))

        assertEquals(FileResult("t1", 0, ok = false, error = TransferErrors.CHECKSUM_MISMATCH), s.peer.receiveMessage())
        assertTrue(s.sink.saved.isEmpty())
        assertTrue(s.sink.kept.isEmpty())
        assertEquals(1, s.sink.aborted)
    }

    @Test
    fun senderStartingOverDiscardsTheKeptPart() = runTest {
        // E.g. an older app, which always starts at 0.
        val s = backgroundScope.start(sink = sinkWithKeptPart(), peerKey = key)
        assertEquals(2, s.offerFiveBytes())

        s.peer.send(FileBegin("t1", 0, offset = 0))
        s.peer.sendBinary(Chunk.encode(0, five))
        s.peer.send(FileEnd("t1", 0, sha(five)))

        assertEquals(FileResult("t1", 0, ok = true), s.peer.receiveMessage())
        assertContentEquals(five, s.sink.saved["f.bin"])
        assertEquals(1, s.sink.discarded)
        assertTrue(s.sink.kept.isEmpty())
    }

    @Test
    fun anotherOffsetIsRefusedAndThePartStaysKept() = runTest {
        val s = backgroundScope.start(sink = sinkWithKeptPart(), peerKey = key)
        assertEquals(2, s.offerFiveBytes())

        s.peer.send(FileBegin("t1", 0, offset = 1))

        assertEquals(FileResult("t1", 0, ok = false, error = TransferErrors.IO_ERROR), s.peer.receiveMessage())
        val kept = s.sink.kept.single()
        assertEquals(false, kept.claimed)
    }

    @Test
    fun cancelBeforeTheFileStartsLeavesThePartKept() = runTest {
        val s = backgroundScope.start(sink = sinkWithKeptPart(), peerKey = key)
        assertEquals(2, s.offerFiveBytes())

        s.peer.send(Cancel("t1"))

        s.session.awaitFinished("t1")
        assertEquals(false, s.sink.kept.single().claimed)
    }

    @Test
    fun connectionLostBeforeTheFileStartsLeavesThePartKept() = runTest {
        val s = backgroundScope.start(sink = sinkWithKeptPart(), peerKey = key)
        assertEquals(2, s.offerFiveBytes())

        s.peer.close()

        s.session.awaitFinished("t1")
        assertEquals(false, s.sink.kept.single().claimed)
    }

    @Test
    fun onlyAPartFromTheSameDeviceWithTheSameNameAndSizeIsUsed() = runTest {
        for (sink in listOf(sinkWithKeptPart(peer = "b".repeat(64)), sinkWithKeptPart(name = "g.bin"), sinkWithKeptPart(size = 6))) {
            val s = backgroundScope.start(sink = sink, peerKey = key)
            assertEquals(0, s.offerFiveBytes())
            assertEquals(false, sink.kept.single().claimed)
            s.peer.close()
        }
    }

    @Test
    fun unpairedPeerNeverResumes() = runTest {
        val s = backgroundScope.start(sink = sinkWithKeptPart(), peerKey = null)
        assertEquals(0, s.offerFiveBytes())
    }

    @Test
    fun keptPartThatChangedOnDiskIsDeleted() = runTest {
        val sink = sinkWithKeptPart()
        sink.kept.single().data = byteArrayOf(1) // shorter than its record says
        val s = backgroundScope.start(sink = sink, peerKey = key)
        assertEquals(2, s.offerFiveBytes())

        s.peer.send(FileBegin("t1", 0, offset = 2))

        assertEquals(FileResult("t1", 0, ok = false, error = TransferErrors.IO_ERROR), s.peer.receiveMessage())
        assertEquals(1, sink.discarded)
        assertTrue(sink.kept.isEmpty())
        val status = assertIs<FileStatus.Failed>(s.session.transfers.value.single().files.single().status)
        assertTrue(status.reason.startsWith("could not resume the file"), status.reason)
    }

    @Test
    fun senderResumesWhereTheReceiverAsks() = runTest {
        val s = backgroundScope.start()
        val data = ByteArray(200_000) { (it * 7).toByte() }
        val id = s.session.send(listOf(MemorySource("big.bin", data)))
        assertIs<TransferOffer>(s.peer.receiveMessage())
        s.peer.send(TransferResponse(id, listOf(FileDecision(0, accept = true, resumeOffset = 150_000))))

        assertEquals(FileBegin(id, 0, offset = 150_000), s.peer.receiveMessage())
        val rest = mutableListOf<Byte>()
        while (true) {
            when (val frame = s.peer.receiveFrame()) {
                is WireFrame.Binary -> rest += assertNotNull(Chunk.decode(frame.bytes)).payload.toList()
                is WireFrame.Text -> {
                    // The checksum is still of the whole file.
                    assertEquals(FileEnd(id, 0, sha(data)), assertIs<Decoded.Ok>(ProtocolCodec.decode(frame.text)).message)
                    break
                }
                null -> error("connection closed")
            }
        }
        assertContentEquals(data.copyOfRange(150_000, 200_000), rest.toByteArray())
        s.peer.send(FileResult(id, 0, ok = true))
        assertEquals(FileStatus.Done(), s.session.awaitFinished(id).files.single().status)
    }

    @Test
    fun resumeOffsetBeyondTheFileStartsItOver() = runTest {
        val s = backgroundScope.start()
        val id = s.session.send(listOf(MemorySource("f.bin", five)))
        assertIs<TransferOffer>(s.peer.receiveMessage())
        s.peer.send(TransferResponse(id, listOf(FileDecision(0, accept = true, resumeOffset = 6))))

        assertEquals(FileBegin(id, 0, offset = 0), s.peer.receiveMessage())
        assertEquals(FileEnd(id, 0, sha(five)), s.peer.receiveMessage())
    }

    @Test
    fun fileShorterThanTheResumeOffsetIsAbandoned() = runTest {
        // The file shrank since the offer, before the part the receiver has.
        val s = backgroundScope.start()
        val id = s.session.send(listOf(MemorySource("f.bin", byteArrayOf(1, 2), size = 5)))
        assertIs<TransferOffer>(s.peer.receiveMessage())
        s.peer.send(TransferResponse(id, listOf(FileDecision(0, accept = true, resumeOffset = 3))))

        assertEquals(Cancel(id, 0), s.peer.receiveMessage())
        assertEquals(
            FileStatus.Failed("the file changed while it was being sent"),
            s.session.awaitFinished(id).files.single().status,
        )
    }

    @Test
    fun invalidOffersAreRefusedWithBadRequest() = runTest {
        val s = backgroundScope.start()
        val invalid = listOf(
            TransferOffer("a", emptyList()),
            TransferOffer("b", listOf(OfferedFile(0, "x", 1), OfferedFile(0, "y", 1))),
            TransferOffer("c", listOf(OfferedFile(0, "x", -1))),
            TransferOffer("d", listOf(OfferedFile(-1, "x", 1))),
            TransferOffer("e", (0..1000).map { OfferedFile(it, "f$it", 1) }),
        )
        for (offer in invalid) {
            s.peer.send(offer, id = "offer-${offer.transferId}")
            val error = assertIs<ErrorMessage>(s.peer.receiveMessage(), "for ${offer.transferId}")
            assertEquals(ErrorCodes.BAD_REQUEST, error.code)
            assertEquals("offer-${offer.transferId}", error.ref)
        }
        assertTrue(s.session.transfers.value.isEmpty())
    }

    @Test
    fun offerCancelledWhileTheUserIsDecidingIsNotAccepted() = runTest {
        val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val s = backgroundScope.start(decider = RecordingDecider { decision.await() })
        s.peer.send(TransferOffer("t1", listOf(OfferedFile(0, "f.bin", 3))))
        s.session.awaitTransfer("t1") { true }
        s.peer.send(com.agnish.linkdrop.protocol.Cancel("t1"))
        decision.complete(true)

        assertEquals(FileStatus.Cancelled, s.session.awaitFinished("t1").files.single().status)
    }

    @Test
    fun peerErrorReferringToTheOfferDeclinesOurTransfer() = runTest {
        val s = backgroundScope.start()
        val id = s.session.send(listOf(MemorySource("a.txt", byteArrayOf(1))))
        val (offerId, offer) = s.peer.receiveWithId()
        assertIs<TransferOffer>(offer)
        s.peer.send(ErrorMessage(ErrorCodes.BAD_REQUEST, "no", ref = offerId))

        val sent = s.session.awaitFinished(id)
        assertEquals(FileStatus.Declined, sent.files.single().status)
        assertEquals("refused: bad_request", sent.note)
    }

    @Test
    fun earlyFailureFromReceiverStopsThatFileAndContinues() = runTest {
        val s = backgroundScope.start()
        val id = s.session.send(
            listOf(MemorySource("big.bin", ByteArray(5 * DEFAULT_MAX_CHUNK_BYTES)), MemorySource("small.txt", byteArrayOf(7))),
        )
        val offer = assertIs<TransferOffer>(s.peer.receiveMessage())
        s.peer.send(TransferResponse(offer.transferId, listOf(FileDecision(0, true), FileDecision(1, true))))
        assertEquals(FileBegin(id, 0), s.peer.receiveMessage())
        assertIs<WireFrame.Binary>(s.peer.receiveFrame())
        s.peer.send(FileResult(id, 0, ok = false, error = TransferErrors.SIZE_MISMATCH))

        // The sender moves on to the next file (some chunks of the first may still be in flight).
        var next = s.peer.receiveMessage()
        while (next is FileEnd) next = s.peer.receiveMessage()
        assertEquals(FileBegin(id, 1), next)
        assertEquals(FileEnd(id, 1, sha(byteArrayOf(7))), s.peer.receiveMessage())
        s.peer.send(FileResult(id, 1, ok = true))

        val sent = s.session.awaitFinished(id)
        assertEquals(FileStatus.Failed("size_mismatch"), sent.files[0].status)
        assertEquals(FileStatus.Done(), sent.files[1].status)
    }

    @Test
    fun withoutTheTransferFeatureTransfersAreRefused() = runTest {
        val s = backgroundScope.start(transferEnabled = false)
        assertFailsWith<IllegalStateException> { s.session.send(listOf(MemorySource("a", byteArrayOf(1)))) }

        s.peer.send(TransferOffer("t1", listOf(OfferedFile(0, "f", 1))), id = "o1")
        val error = assertIs<ErrorMessage>(s.peer.receiveMessage())
        assertEquals(ErrorCodes.BAD_REQUEST, error.code)
        assertEquals("o1", error.ref)

        s.peer.sendBinary(Chunk.encode(0, byteArrayOf(1)))
        assertEquals(ErrorCodes.BAD_REQUEST, assertIs<ErrorMessage>(s.peer.receiveMessage()).code)
        assertNull(s.peer.receiveFrame()) // connection closed
    }

    private fun session(channel: MemoryChannel, transferEnabled: Boolean = true) = TransferSession(
        channel, "Peer", transferEnabled,
        sendChunkBytes = 1024, receiveChunkBytes = 1024, sink = MemorySink(),
        decider = RecordingDecider.acceptAll(), context = EmptyCoroutineContext,
    )

    @Test
    fun normalCloseEndsRunWithoutAReason() = runTest {
        val (mine, theirs) = MemoryChannel.pair()
        val reason = async { session(mine).run() }
        theirs.close()
        assertNull(reason.await())
    }

    @Test
    fun networkErrorEndsRunWithItsMessage() = runTest {
        val (mine, _) = MemoryChannel.pair()
        val reason = async { session(mine).run() }
        mine.fail(RuntimeException("sent ping but didn't receive pong within 30000ms"))
        assertEquals("sent ping but didn't receive pong within 30000ms", reason.await())
    }

    @Test
    fun closingBecauseOfBrokenFramesEndsRunWithTheRule() = runTest {
        val (mine, theirs) = MemoryChannel.pair()
        val reason = async { session(mine, transferEnabled = false).run() }
        theirs.sendBinary(byteArrayOf(0, 0, 0, 0, 1))
        assertEquals("closed: file data without the transfer feature", reason.await())
    }

    @Test
    fun unknownTypesKeepTheSessionOpenAndMalformedFramesAreLimited() = runTest {
        val s = backgroundScope.start()
        s.peer.sendText("""{"v":1,"type":"futureThing","id":"x1","body":{}}""")
        val unknown = assertIs<ErrorMessage>(s.peer.receiveMessage())
        assertEquals(ErrorCodes.UNSUPPORTED_TYPE, unknown.code)
        assertEquals("x1", unknown.ref)

        repeat(TransferSession.MAX_MALFORMED_FRAMES) {
            s.peer.sendText("not json")
            assertEquals(ErrorCodes.MALFORMED, assertIs<ErrorMessage>(s.peer.receiveMessage()).code)
        }
        assertNull(s.peer.receiveFrame()) // closed after the last one
    }
}
