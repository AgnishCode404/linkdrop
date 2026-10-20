package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.FrameChannel
import com.agnish.linkdrop.protocol.WireFrame
import com.agnish.linkdrop.transfer.FileReader
import com.agnish.linkdrop.transfer.FileSource
import com.agnish.linkdrop.transfer.OfferDecider
import com.agnish.linkdrop.transfer.TransferProgress
import com.agnish.linkdrop.transfer.TransferSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Retry in the desktop window: offered only for the same phone, and only once. */
class DesktopStateTest {

    /** A connection that never delivers anything; [ended] makes it end at once, as a lost one. */
    private class FakeChannel(ended: Boolean) : FrameChannel {
        override val incoming: ReceiveChannel<WireFrame> = Channel<WireFrame>().apply { if (ended) close() }

        override suspend fun sendText(text: String) = awaitCancellation()

        override suspend fun sendBinary(bytes: ByteArray) = awaitCancellation()

        override fun close(code: Int, reason: String) = Unit
    }

    private class NamedFile(override val name: String) : FileSource {
        override val size = 10L
        override val mimeType: String? = null

        override suspend fun open(): FileReader = error("not read in these tests")
    }

    private fun session(ended: Boolean) = TransferSession(
        FakeChannel(ended), "Phone", transferEnabled = true, sendChunkBytes = 1024, receiveChunkBytes = 1024,
        sink = null, decider = OfferDecider { false },
    )

    private val key = "a".repeat(64)

    /** A phone connection that ended while the desktop was sending it [files]; now in the history. */
    private fun lostConnection(state: DesktopState, files: List<FileSource>): PeerConnection = runBlocking {
        val session = session(ended = true)
        session.send(files)
        session.run()
        PeerConnection("Pixel", "192.168.1.5", session, transferEnabled = true, peerId = "phone-1", peerKey = key)
            .also { state.connected(it); state.disconnected(it) }
    }

    private fun connected(state: DesktopState, peerId: String = "phone-1", peerKey: String? = key, transferEnabled: Boolean = true) =
        PeerConnection("Pixel", "192.168.1.6", session(ended = false), transferEnabled, peerId, peerKey)
            .also { state.connected(it) }

    @Test
    fun retrySendsTheInterruptedFilesToTheSamePhoneOnce() {
        val state = DesktopState()
        val files = listOf(NamedFile("a.jpg"), NamedFile("b.jpg"))
        lostConnection(state, files)
        val past = state.history.value.single()
        assertNull(state.retryTarget(past), "the phone is not connected")

        val again = connected(state)
        assertSame(again, state.retryTarget(past))
        state.retry(past)
        state.retry(past) // a second click does nothing

        val sent: List<TransferProgress> = again.session.transfers.value
        assertEquals(listOf(listOf("a.jpg", "b.jpg")), sent.map { t -> t.files.map { it.name } })
        val after = state.history.value.single()
        assertTrue(after.retried)
        assertNull(state.retryTarget(after))
    }

    @Test
    fun retryIsOnlyOfferedForTheSamePhoneWithTheSameCertificate() {
        val state = DesktopState()
        lostConnection(state, listOf(NamedFile("a.jpg")))
        val past = state.history.value.single()

        connected(state, peerKey = "b".repeat(64)) // same id, other certificate
        connected(state, peerId = "phone-2") // other phone
        connected(state, transferEnabled = false) // the same phone, but an app too old to transfer
        assertNull(state.retryTarget(past))

        val right = connected(state)
        assertNotNull(state.retryTarget(past))
        state.retry(past)
        assertEquals(1, right.session.transfers.value.size)
    }

    @Test
    fun nothingToRetryWithoutInterruptedFiles() {
        val state = DesktopState()
        val connection = lostConnection(state, listOf(NamedFile("a.jpg")))
        connected(state)
        val other = PastTransfer(connection, state.history.value.single().progress.copy(transferId = "unknown"))
        assertNull(state.retryTarget(other))
    }
}
