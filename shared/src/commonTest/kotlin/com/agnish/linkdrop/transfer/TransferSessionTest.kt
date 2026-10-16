package com.agnish.linkdrop.transfer

import com.agnish.linkdrop.protocol.DEFAULT_MAX_CHUNK_BYTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Two real sessions talking to each other in memory: "desktop" and "phone". */
class TransferSessionTest {

    private class Peers(
        val desktop: TransferSession,
        val phone: TransferSession,
        val desktopSink: MemorySink,
        val phoneSink: MemorySink,
        val desktopChannel: MemoryChannel,
        val phoneChannel: MemoryChannel,
    )

    private fun CoroutineScope.connect(
        desktopDecider: OfferDecider = RecordingDecider.acceptAll(),
        phoneDecider: OfferDecider = RecordingDecider.acceptAll(),
        desktopSink: MemorySink = MemorySink(),
        phoneSink: MemorySink = MemorySink(),
    ): Peers {
        val (d, p) = MemoryChannel.pair()
        fun session(channel: MemoryChannel, peer: String, sink: MemorySink, decider: OfferDecider) = TransferSession(
            channel, peer, transferEnabled = true,
            sendChunkBytes = DEFAULT_MAX_CHUNK_BYTES, receiveChunkBytes = DEFAULT_MAX_CHUNK_BYTES,
            sink = sink, decider = decider, context = EmptyCoroutineContext,
        )
        val peers = Peers(
            session(d, "Phone", desktopSink, desktopDecider),
            session(p, "Desktop", phoneSink, phoneDecider),
            desktopSink, phoneSink, d, p,
        )
        launch { peers.desktop.run() }
        launch { peers.phone.run() }
        return peers
    }

    private fun bytes(size: Int, seed: Int = size) = Random(seed).nextBytes(size)

    @Test
    fun oneFileArrivesIntact() = runTest {
        val peers = backgroundScope.connect()
        val data = bytes(200_000) // several chunks
        val id = peers.phone.send(listOf(MemorySource("photo.jpg", data, mimeType = "image/jpeg")))

        val sent = peers.phone.awaitFinished(id)
        val received = peers.desktop.awaitFinished(id)
        assertEquals(FileStatus.Done(), sent.files.single().status)
        assertEquals(FileStatus.Done("photo.jpg"), received.files.single().status)
        assertEquals(Direction.Outgoing, sent.direction)
        assertEquals(Direction.Incoming, received.direction)
        assertContentEquals(data, peers.desktopSink.saved["photo.jpg"])
    }

    @Test
    fun queueOfThreeFilesArrivesInOrder() = runTest {
        val desktopDecider = RecordingDecider.acceptAll()
        val peers = backgroundScope.connect(desktopDecider = desktopDecider)
        val a = bytes(150_000)
        val c = bytes(1)
        val id = peers.phone.send(listOf(MemorySource("a.bin", a), MemorySource("empty.txt", ByteArray(0)), MemorySource("c.bin", c)))

        val received = peers.desktop.awaitFinished(id)
        assertTrue(received.files.all { it.status is FileStatus.Done }, "$received")
        assertEquals(listOf("a.bin", "empty.txt", "c.bin"), peers.desktopSink.saved.keys.toList())
        assertContentEquals(a, peers.desktopSink.saved["a.bin"])
        assertContentEquals(ByteArray(0), peers.desktopSink.saved["empty.txt"])
        assertContentEquals(c, peers.desktopSink.saved["c.bin"])
        assertEquals(1, desktopDecider.asked.size)
        assertEquals(150_001, desktopDecider.asked.single().totalBytes)
    }

    @Test
    fun secondTransferIsQueuedBehindTheFirst() = runTest {
        val desktopDecider = RecordingDecider.acceptAll()
        val peers = backgroundScope.connect(desktopDecider = desktopDecider)
        val first = peers.phone.send(listOf(MemorySource("one.txt", bytes(100_000))))
        val second = peers.phone.send(listOf(MemorySource("two.txt", bytes(10))))

        peers.desktop.awaitFinished(first)
        peers.desktop.awaitFinished(second)
        assertEquals(listOf(first, second), desktopDecider.asked.map { it.transferId })
        assertEquals(listOf("one.txt", "two.txt"), peers.desktopSink.saved.keys.toList())
    }

    @Test
    fun sameNameTwiceIsNotOverwritten() = runTest {
        val peers = backgroundScope.connect()
        val first = peers.phone.send(listOf(MemorySource("photo.jpg", bytes(5, seed = 1))))
        val second = peers.phone.send(listOf(MemorySource("photo.jpg", bytes(5, seed = 2))))

        peers.desktop.awaitFinished(first)
        val received = peers.desktop.awaitFinished(second)
        assertEquals(FileStatus.Done("photo (1).jpg"), received.files.single().status)
        assertContentEquals(bytes(5, seed = 1), peers.desktopSink.saved["photo.jpg"])
        assertContentEquals(bytes(5, seed = 2), peers.desktopSink.saved["photo (1).jpg"])
    }

    @Test
    fun declinedOfferSavesNothing() = runTest {
        val peers = backgroundScope.connect(desktopDecider = RecordingDecider.declineAll())
        val id = peers.phone.send(listOf(MemorySource("a.txt", bytes(10)), MemorySource("b.txt", bytes(10))))

        assertTrue(peers.phone.awaitFinished(id).files.all { it.status == FileStatus.Declined })
        assertTrue(peers.desktop.awaitFinished(id).files.all { it.status == FileStatus.Declined })
        assertTrue(peers.desktopSink.saved.isEmpty())
    }

    @Test
    fun fileWithUnusableNameIsDeclinedOthersArrive() = runTest {
        val desktopDecider = RecordingDecider.acceptAll()
        val peers = backgroundScope.connect(desktopDecider = desktopDecider)
        val id = peers.phone.send(listOf(MemorySource("ok.txt", bytes(10)), MemorySource("../", bytes(10))))

        val sent = peers.phone.awaitFinished(id)
        assertIs<FileStatus.Done>(sent.files[0].status)
        assertEquals(FileStatus.Declined, sent.files[1].status)
        val received = peers.desktop.awaitFinished(id)
        assertEquals("(unusable name)", received.files[1].name)
        // The user is only asked about the files that can be accepted.
        assertEquals(listOf("ok.txt"), desktopDecider.asked.single().files.map { it.name })
        assertEquals(listOf("ok.txt"), peers.desktopSink.saved.keys.toList())
    }

    @Test
    fun notEnoughSpaceDeclinesWithoutAsking() = runTest {
        val desktopDecider = RecordingDecider.acceptAll()
        val tooLittle = MemorySink(free = TransferSession.FREE_SPACE_MARGIN_BYTES + 100)
        val peers = backgroundScope.connect(desktopDecider = desktopDecider, desktopSink = tooLittle)
        val id = peers.phone.send(listOf(MemorySource("big.bin", bytes(200))))

        assertEquals(FileStatus.Declined, peers.phone.awaitFinished(id).files.single().status)
        assertEquals("not enough free space", peers.desktop.awaitFinished(id).note)
        assertTrue(desktopDecider.asked.isEmpty())
    }

    @Test
    fun unansweredOfferIsDeclinedAfterTimeout() = runTest {
        // Virtual time: the 120 s timeout passes instantly.
        val peers = backgroundScope.connect(desktopDecider = RecordingDecider.neverAnswer())
        val id = peers.phone.send(listOf(MemorySource("a.txt", bytes(10))))

        assertEquals(FileStatus.Declined, peers.phone.awaitFinished(id).files.single().status)
        assertEquals("no answer in time", peers.desktop.awaitFinished(id).note)
    }

    @Test
    fun bothDirectionsAtOnce() = runTest {
        val peers = backgroundScope.connect()
        val toDesktop = bytes(300_000, seed = 1)
        val toPhone = bytes(300_000, seed = 2)
        val up = peers.phone.send(listOf(MemorySource("up.bin", toDesktop)))
        val down = peers.desktop.send(listOf(MemorySource("down.bin", toPhone)))

        peers.desktop.awaitFinished(up)
        peers.phone.awaitFinished(down)
        assertContentEquals(toDesktop, peers.desktopSink.saved["up.bin"])
        assertContentEquals(toPhone, peers.phoneSink.saved["down.bin"])
    }

    @Test
    fun senderCancelsMidFile() = runTest {
        val peers = backgroundScope.connect()
        val source = MemorySource("big.bin", bytes(200_000), gateAfterBytes = DEFAULT_MAX_CHUNK_BYTES)
        val id = peers.phone.send(listOf(source, MemorySource("next.bin", bytes(10))))
        peers.desktop.awaitTransfer(id) { it.files[0].status is FileStatus.Transferring }

        peers.phone.cancel(id)
        source.gate.complete(Unit)

        assertTrue(peers.phone.awaitFinished(id).files.all { it.status == FileStatus.Cancelled })
        assertTrue(peers.desktop.awaitFinished(id).files.all { it.status == FileStatus.Cancelled })
        assertTrue(peers.desktopSink.saved.isEmpty())
        assertEquals(1, peers.desktopSink.aborted)

        // The connection still works afterwards.
        val after = peers.phone.send(listOf(MemorySource("after.txt", bytes(10))))
        assertIs<FileStatus.Done>(peers.desktop.awaitFinished(after).files.single().status)
    }

    @Test
    fun receiverCancelsMidFile() = runTest {
        val peers = backgroundScope.connect()
        val source = MemorySource("big.bin", bytes(200_000), gateAfterBytes = DEFAULT_MAX_CHUNK_BYTES)
        val id = peers.phone.send(listOf(source))
        peers.desktop.awaitTransfer(id) { it.files[0].status is FileStatus.Transferring }

        peers.desktop.cancel(id)
        peers.desktop.awaitFinished(id)
        source.gate.complete(Unit)

        assertEquals(FileStatus.Cancelled, peers.phone.awaitFinished(id).files.single().status)
        assertEquals(FileStatus.Cancelled, peers.desktop.awaitFinished(id).files.single().status)
        assertTrue(peers.desktopSink.saved.isEmpty())

        val after = peers.phone.send(listOf(MemorySource("after.txt", bytes(10))))
        assertIs<FileStatus.Done>(peers.desktop.awaitFinished(after).files.single().status)
    }

    @Test
    fun cancelBeforeStartNeverSendsTheOffer() = runTest {
        val desktopDecider = RecordingDecider.acceptAll()
        val peers = backgroundScope.connect(desktopDecider = desktopDecider)
        val gated = MemorySource("first.bin", bytes(200_000), gateAfterBytes = DEFAULT_MAX_CHUNK_BYTES)
        val first = peers.phone.send(listOf(gated))
        val queued = peers.phone.send(listOf(MemorySource("queued.bin", bytes(10))))
        peers.phone.cancel(queued)
        gated.gate.complete(Unit)

        peers.desktop.awaitFinished(first)
        assertEquals(FileStatus.Cancelled, peers.phone.awaitFinished(queued).files.single().status)
        assertEquals(listOf(first), desktopDecider.asked.map { it.transferId })
    }

    @Test
    fun connectionLostMidFileFailsBothSides() = runTest {
        val peers = backgroundScope.connect()
        val source = MemorySource("big.bin", bytes(200_000), gateAfterBytes = DEFAULT_MAX_CHUNK_BYTES)
        val id = peers.phone.send(listOf(source, MemorySource("never.bin", bytes(10))))
        peers.desktop.awaitTransfer(id) { it.files[0].status is FileStatus.Transferring }

        peers.phoneChannel.close()

        val received = peers.desktop.awaitFinished(id)
        assertEquals(FileStatus.Failed("connection lost"), received.files[0].status)
        assertEquals(1, peers.desktopSink.aborted)
        assertTrue(peers.desktopSink.saved.isEmpty())
        source.gate.complete(Unit)
        assertTrue(peers.phone.awaitFinished(id).files.all { it.status is FileStatus.Failed })
    }

    @Test
    fun readErrorFailsThatFileAndContinues() = runTest {
        val peers = backgroundScope.connect()
        val broken = MemorySource("broken.bin", bytes(200_000), failAfterBytes = DEFAULT_MAX_CHUNK_BYTES)
        val id = peers.phone.send(listOf(broken, MemorySource("fine.txt", bytes(10))))

        val sent = peers.phone.awaitFinished(id)
        assertEquals(FileStatus.Failed("could not read: disk error"), sent.files[0].status)
        assertIs<FileStatus.Done>(sent.files[1].status)
        val received = peers.desktop.awaitFinished(id)
        assertEquals(FileStatus.Cancelled, received.files[0].status)
        assertEquals(listOf("fine.txt"), peers.desktopSink.saved.keys.toList())
    }

    @Test
    fun fileThatGrewWhileSendingIsAbandoned() = runTest {
        val peers = backgroundScope.connect()
        // Declares 10 bytes but contains 20.
        val id = peers.phone.send(listOf(MemorySource("grew.txt", bytes(20), size = 10), MemorySource("ok.txt", bytes(5))))

        val sent = peers.phone.awaitFinished(id)
        assertEquals(FileStatus.Failed("the file changed while it was being sent"), sent.files[0].status)
        assertIs<FileStatus.Done>(sent.files[1].status)
        assertEquals(listOf("ok.txt"), peers.desktopSink.saved.keys.toList())
    }
}
