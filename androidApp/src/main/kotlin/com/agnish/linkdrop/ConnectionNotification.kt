package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Pairing
import com.agnish.linkdrop.transfer.Direction
import com.agnish.linkdrop.transfer.FileStatus
import com.agnish.linkdrop.transfer.TransferProgress

/**
 * What the connection notification shows. [progress]: null for no bar; a bar with a null
 * fraction moves without a known end. [actionLabel]: the notification's button, which ends the
 * connection (or the attempt) like the app's own button.
 */
data class NotificationContent(
    val title: String,
    val text: String?,
    val progress: Progress?,
    val actionLabel: String,
) {
    data class Progress(val fraction: Float?)
}

/** Builds [NotificationContent] from the connection's state. No Android calls, so it is unit-tested. */
object ConnectionNotification {
    /**
     * Android (15+) lets a data-sync foreground service run for 6 hours a day in the background;
     * opening the app resets that. When it runs out, LinkDrop disconnects and says so.
     */
    const val TIME_LIMIT_REASON = "Android's background limit (6 hours a day) was reached"
    const val TIME_LIMIT_TITLE = "LinkDrop disconnected"
    const val TIME_LIMIT_TEXT = "Android allows background transfers for 6 hours a day. Open LinkDrop to reconnect."

    /** Whether the connection service should run: from the first connection attempt until it ends. */
    fun shouldRun(state: PhoneConnection.State): Boolean = state !is PhoneConnection.State.Disconnected

    /** Null when there is nothing to show (disconnected: the service stops). */
    fun content(state: PhoneConnection.State, transfers: List<TransferProgress>): NotificationContent? = when (state) {
        is PhoneConnection.State.Disconnected -> null
        is PhoneConnection.State.Connecting -> NotificationContent(
            "Connecting to ${state.address.host}:${state.address.port}…",
            null,
            NotificationContent.Progress(null),
            "Cancel",
        )
        is PhoneConnection.State.ConfirmPairing -> NotificationContent(
            "Pairing with a new desktop",
            "Open LinkDrop to compare the code ${Pairing.formatCode(state.code)}",
            null,
            "Cancel",
        )
        is PhoneConnection.State.WaitingForDesktop -> NotificationContent(
            "Waiting for the desktop to allow the connection",
            "Code ${Pairing.formatCode(state.code)}",
            NotificationContent.Progress(null),
            "Cancel",
        )
        is PhoneConnection.State.Connected -> transferContent(state.peerName, transfers) ?: NotificationContent(
            "Connected to \"${state.peerName}\"",
            if (state.secure) "Encrypted, paired" else "Not encrypted (development build)",
            null,
            "Disconnect",
        )
    }

    /** While anything is unfinished: the file moving now, or what is waiting. Null when idle. */
    private fun transferContent(peerName: String, transfers: List<TransferProgress>): NotificationContent? {
        val active = transfers.filter { !it.finished }
        if (active.isEmpty()) return null
        val more = if (active.size > 1) " (+${active.size - 1} more)" else ""
        for (transfer in active) {
            val file = transfer.files.firstOrNull { it.status is FileStatus.Transferring } ?: continue
            val bytes = (file.status as FileStatus.Transferring).bytes
            val verb = if (transfer.direction == Direction.Outgoing) "Sending" else "Receiving"
            val fraction = if (file.size == 0L) 1f else (bytes.toFloat() / file.size).coerceIn(0f, 1f)
            return NotificationContent(
                "$verb ${file.name}$more",
                "${formatBytes(bytes)} / ${formatBytes(file.size)} · \"$peerName\"",
                NotificationContent.Progress(fraction),
                "Disconnect",
            )
        }
        // Nothing is moving: an offer waits for an answer, or a transfer waits its turn.
        val first = active.first()
        val files = "${first.files.size} file(s)"
        val text = if (first.direction == Direction.Outgoing) {
            "Waiting to send $files to \"$peerName\"$more"
        } else {
            "\"$peerName\" wants to send $files: open LinkDrop to answer$more"
        }
        return NotificationContent("Connected to \"$peerName\"", text, NotificationContent.Progress(null), "Disconnect")
    }
}
