package com.agnish.linkdrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agnish.linkdrop.transfer.Direction
import com.agnish.linkdrop.transfer.FileProgress
import com.agnish.linkdrop.transfer.FileStatus
import com.agnish.linkdrop.transfer.TransferProgress
import com.agnish.linkdrop.transfer.TransferSession
import java.util.Locale

/** Files listed per transfer or offer before "… and N more". */
private const val MAX_LISTED_FILES = 20

@Composable
fun PhoneCard(connection: PeerConnection, onSend: () -> Unit) {
    val transfers by connection.session.transfers.collectAsState()
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StatusLine("\"${connection.name}\"", Tone.Active)
                    Text(
                        connection.address + if (connection.peerKey != null) " · encrypted, paired" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrimaryButton("Send files…", onClick = onSend, enabled = connection.transferEnabled)
            }
            if (!connection.transferEnabled) {
                Text(
                    "This phone's LinkDrop app is too old to transfer files.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            transfers.asReversed().forEach { transfer ->
                TransferCard(connection.name, transfer, onCancel = { connection.session.cancel(transfer.transferId) })
            }
        }
    }
}

@Composable
fun OfferCard(pending: PendingOffer) {
    val offer = pending.offer
    // Raised: it waits for an answer.
    AppCard(modifier = Modifier.fillMaxWidth(), raised = true) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "\"${offer.peerName}\" wants to send ${offer.files.size} file(s), ${formatBytes(offer.totalBytes)}",
                style = MaterialTheme.typography.titleMedium,
            )
            offer.files.take(MAX_LISTED_FILES).forEach {
                Text("• ${it.name} (${formatBytes(it.size)})", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (offer.files.size > MAX_LISTED_FILES) {
                Text("… and ${offer.files.size - MAX_LISTED_FILES} more", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Decline on the left, Accept on the right, as in the pairing dialog.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                DangerButton("Decline", onClick = { pending.answer.complete(false) }, modifier = Modifier.weight(1f))
                PrimaryButton("Accept", onClick = { pending.answer.complete(true) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * One transfer. [onRetry]: shown as Retry (the other device is connected again and some files
 * were cut off). [retried]: Retry was used already.
 */
@Composable
fun TransferCard(
    peerName: String,
    transfer: TransferProgress,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)? = null,
    retried: Boolean = false,
) {
    AppCard(modifier = Modifier.fillMaxWidth(), outlined = true) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val direction = if (transfer.direction == Direction.Outgoing) "To" else "From"
                Text(
                    "$direction \"$peerName\": ${transfer.files.size} file(s)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (!transfer.finished && onCancel != null) DangerButton("Cancel", onClick = onCancel)
                if (onRetry != null) PrimaryButton("Retry", onClick = onRetry)
                if (retried) Tag("Retried", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            transfer.note?.let { StatusLine(it, Tone.Error) }
            transfer.files.take(MAX_LISTED_FILES).forEach { FileRow(it) }
            if (transfer.files.size > MAX_LISTED_FILES) {
                Text("… and ${transfer.files.size - MAX_LISTED_FILES} more", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FileRow(file: FileProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                statusText(file),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(file.status),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        val status = file.status
        if (status is FileStatus.Transferring) {
            val fraction = if (file.size == 0L) 1f else (status.bytes.toFloat() / file.size).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                color = MaterialTheme.colorScheme.primary,
                trackColor = LinkDropColors.Track,
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
        }
    }
}

private fun statusText(file: FileProgress): String = when (val status = file.status) {
    FileStatus.Waiting -> "waiting · ${formatBytes(file.size)}"
    is FileStatus.Transferring -> "${formatBytes(status.bytes)} / ${formatBytes(file.size)}"
    is FileStatus.Done -> status.savedAs?.takeIf { it != file.name }?.let { "done, saved as $it" } ?: "done"
    is FileStatus.Failed -> "failed: ${status.reason}"
    FileStatus.Declined -> "declined"
    FileStatus.Cancelled -> "cancelled"
}

/** Green when done, blue while moving, amber when it can be retried, red when it failed. */
@Composable
private fun statusColor(status: FileStatus): Color = when (status) {
    is FileStatus.Done -> LinkDropColors.Success
    is FileStatus.Transferring -> MaterialTheme.colorScheme.primary
    is FileStatus.Failed -> when (status.reason) {
        TransferSession.CONNECTION_LOST, TransferSession.CONNECTION_LOST_KEPT -> LinkDropColors.Warning
        else -> MaterialTheme.colorScheme.error
    }
    FileStatus.Waiting, FileStatus.Declined, FileStatus.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
