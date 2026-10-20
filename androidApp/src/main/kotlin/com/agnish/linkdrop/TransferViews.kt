package com.agnish.linkdrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agnish.linkdrop.transfer.Direction
import com.agnish.linkdrop.transfer.FileProgress
import com.agnish.linkdrop.transfer.FileStatus
import com.agnish.linkdrop.transfer.TransferProgress
import java.util.Locale

// Same cards as desktopApp/TransferViews.kt. Kept as a copy for now: sharing them would need a
// Compose UI module, which `shared` deliberately is not.

/** Files listed per transfer or offer before "… and N more". */
private const val MAX_LISTED_FILES = 20

@Composable
fun OfferCard(pending: PendingOffer) {
    val offer = pending.offer
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "\"${offer.peerName}\" wants to send ${offer.files.size} file(s), ${formatBytes(offer.totalBytes)}",
                style = MaterialTheme.typography.titleMedium,
            )
            offer.files.take(MAX_LISTED_FILES).forEach {
                Text("• ${it.name} (${formatBytes(it.size)})", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (offer.files.size > MAX_LISTED_FILES) Text("… and ${offer.files.size - MAX_LISTED_FILES} more")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pending.answer.complete(true) }) { Text("Accept") }
                OutlinedButton(onClick = { pending.answer.complete(false) }) { Text("Decline") }
            }
        }
    }
}

/**
 * One transfer. [onRetry]: shown as Retry (the desktop is connected again and some files were cut
 * off). [retried]: Retry was used already.
 */
@Composable
fun TransferCard(
    peerName: String,
    transfer: TransferProgress,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)? = null,
    retried: Boolean = false,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val direction = if (transfer.direction == Direction.Outgoing) "To" else "From"
                Text(
                    "$direction \"$peerName\": ${transfer.files.size} file(s)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (!transfer.finished && onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
                if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
                if (retried) Text("Retried", style = MaterialTheme.typography.bodySmall)
            }
            transfer.note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            transfer.files.take(MAX_LISTED_FILES).forEach { FileRow(it) }
            if (transfer.files.size > MAX_LISTED_FILES) Text("… and ${transfer.files.size - MAX_LISTED_FILES} more")
        }
    }
}

@Composable
private fun FileRow(file: FileProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
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

@Composable
private fun statusColor(status: FileStatus): Color = when (status) {
    is FileStatus.Done -> MaterialTheme.colorScheme.primary
    is FileStatus.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
