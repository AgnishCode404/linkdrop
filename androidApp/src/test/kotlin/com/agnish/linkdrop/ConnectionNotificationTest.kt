package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.FrameChannel
import com.agnish.linkdrop.protocol.ServerAddress
import com.agnish.linkdrop.protocol.WireFrame
import com.agnish.linkdrop.transfer.Direction
import com.agnish.linkdrop.transfer.FileProgress
import com.agnish.linkdrop.transfer.FileStatus
import com.agnish.linkdrop.transfer.OfferDecider
import com.agnish.linkdrop.transfer.TransferProgress
import com.agnish.linkdrop.transfer.TransferSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionNotificationTest {
    private val address = ServerAddress("192.168.29.75", 47800)

    private object NoChannel : FrameChannel {
        override val incoming = Channel<WireFrame>()
        override suspend fun sendText(text: String) = Unit
        override suspend fun sendBinary(bytes: ByteArray) = Unit
        override fun close(code: Int, reason: String) = Unit
    }

    private fun connected(peerKey: String? = "a".repeat(64)) = PhoneConnection.State.Connected(
        address, "desktop-1", "archlinux",
        TransferSession(NoChannel, "archlinux", true, 1024, 1024, null, OfferDecider { false }),
        transferEnabled = true, peerKey = peerKey,
    )

    private fun transfer(direction: Direction, vararg files: FileProgress) = TransferProgress("t", direction, files.toList())

    @Test
    fun runsFromTheFirstAttemptUntilDisconnected() {
        assertFalse(ConnectionNotification.shouldRun(PhoneConnection.State.Disconnected()))
        assertFalse(ConnectionNotification.shouldRun(PhoneConnection.State.Disconnected("connection lost")))
        assertTrue(ConnectionNotification.shouldRun(PhoneConnection.State.Connecting(address)))
        assertTrue(ConnectionNotification.shouldRun(PhoneConnection.State.WaitingForDesktop(address, "482913")))
        assertTrue(ConnectionNotification.shouldRun(connected()))
        assertNull(ConnectionNotification.content(PhoneConnection.State.Disconnected(), emptyList()))
    }

    @Test
    fun connectingAndPairing() {
        assertEquals(
            NotificationContent("Connecting to 192.168.29.75:47800…", null, NotificationContent.Progress(null), "Cancel"),
            ConnectionNotification.content(PhoneConnection.State.Connecting(address), emptyList()),
        )
        val pairing = ConnectionNotification.content(
            PhoneConnection.State.ConfirmPairing(address, "482913", CompletableDeferred()), emptyList(),
        )!!
        assertEquals("Open LinkDrop to compare the code 482 913", pairing.text)
        assertEquals("Cancel", pairing.actionLabel)
        assertEquals("Code 482 913", ConnectionNotification.content(PhoneConnection.State.WaitingForDesktop(address, "482913"), emptyList())!!.text)
    }

    @Test
    fun idleConnection() {
        assertEquals(
            NotificationContent("Connected to \"archlinux\"", "Encrypted, paired", null, "Disconnect"),
            ConnectionNotification.content(connected(), emptyList()),
        )
        assertEquals("Not encrypted (development build)", ConnectionNotification.content(connected(peerKey = null), emptyList())!!.text)
        // Finished transfers do not count.
        val done = transfer(Direction.Outgoing, FileProgress(0, "a.jpg", 10, FileStatus.Done()))
        assertEquals("Encrypted, paired", ConnectionNotification.content(connected(), listOf(done))!!.text)
    }

    @Test
    fun fileBeingTransferredShowsItsProgress() {
        val sending = transfer(
            Direction.Outgoing,
            FileProgress(0, "photo.jpg", 1000, FileStatus.Done()),
            FileProgress(1, "holiday.mp4", 2048L * 1024 * 1024, FileStatus.Transferring(512L * 1024 * 1024)),
        )
        val content = ConnectionNotification.content(connected(), listOf(sending))!!
        assertEquals("Sending holiday.mp4", content.title)
        assertEquals("512.0 MB / 2.00 GB · \"archlinux\"", content.text)
        assertEquals(NotificationContent.Progress(0.25f), content.progress)
        assertEquals("Disconnect", content.actionLabel)

        val receiving = transfer(Direction.Incoming, FileProgress(0, "scan.pdf", 100, FileStatus.Transferring(50)))
        val both = ConnectionNotification.content(connected(), listOf(receiving, sending))!!
        assertEquals("Receiving scan.pdf (+1 more)", both.title)
    }

    @Test
    fun waitingTransfers() {
        val offer = transfer(Direction.Incoming, FileProgress(0, "a.jpg", 10, FileStatus.Waiting), FileProgress(1, "b.jpg", 10, FileStatus.Waiting))
        assertEquals(
            "\"archlinux\" wants to send 2 file(s): open LinkDrop to answer",
            ConnectionNotification.content(connected(), listOf(offer))!!.text,
        )
        val queued = transfer(Direction.Outgoing, FileProgress(0, "a.jpg", 10, FileStatus.Waiting))
        val content = ConnectionNotification.content(connected(), listOf(queued))!!
        assertEquals("Waiting to send 1 file(s) to \"archlinux\"", content.text)
        assertEquals(NotificationContent.Progress(null), content.progress)
    }

    @Test
    fun timeLimitNoticeTellsWhatToDo() {
        // The notification (outside the app) says how to continue; the app's own message does not
        // tell the user to open the app they are looking at.
        assertTrue("Open LinkDrop to reconnect" in ConnectionNotification.TIME_LIMIT_TEXT)
        assertFalse("Open LinkDrop" in ConnectionNotification.TIME_LIMIT_REASON)
        assertTrue("6 hours" in ConnectionNotification.TIME_LIMIT_REASON)
    }
}
