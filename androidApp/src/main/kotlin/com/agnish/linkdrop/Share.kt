package com.agnish.linkdrop

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Files (or something else) handed to LinkDrop through another app's share menu. */
sealed interface ShareRequest {
    /** [skipped] counts shared items that were refused (see [isShareableUri]). */
    data class Files(val uris: List<Uri>, val skipped: Int) : ShareRequest

    data class NothingToSend(val reason: String) : ShareRequest

    companion object {
        /** Returns null if [intent] is not a share (e.g. LinkDrop was opened from the launcher). */
        fun from(intent: Intent?): ShareRequest? {
            val action = intent?.action
            if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
            // Extras from another app are untrusted: a wrong type must not crash us.
            val candidates = (runCatching { streamExtras(intent) }.getOrDefault(emptyList()) + clipUris(intent)).distinct()
            val accepted = candidates.filter { isShareableUri(it.toString()) }
            return when {
                accepted.isNotEmpty() -> Files(accepted, skipped = candidates.size - accepted.size)
                candidates.isNotEmpty() -> NothingToSend("These items cannot be sent: only files shared by apps are supported.")
                else -> NothingToSend("LinkDrop sends files, not text or links.")
            }
        }

        @Suppress("DEPRECATION") // the typed versions only exist from Android 13
        private fun streamExtras(intent: Intent): List<Uri> = if (intent.action == Intent.ACTION_SEND) {
            listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                },
            )
        } else {
            (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                ).orEmpty()
        }

        /** The share sheet also puts the items in ClipData (which carries the read permission). */
        private fun clipUris(intent: Intent): List<Uri> {
            val clip = intent.clipData ?: return emptyList()
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
    }
}

/**
 * Only `content://` items are sent. A `file://` item could point at LinkDrop's own private files;
 * a malicious app could "share" one to make LinkDrop send it to the desktop.
 */
fun isShareableUri(uri: String): Boolean = uri.startsWith("content://", ignoreCase = true)

/** Where a share stands, for [ShareCard]. */
sealed interface ShareProgress {
    data object Reading : ShareProgress
    data object NeedsAddress : ShareProgress
    data object Connecting : ShareProgress
    data class CannotSend(val reason: String) : ShareProgress
    data class Sending(val peerName: String, val finished: Boolean) : ShareProgress
}

@Composable
fun ShareCard(
    share: ShareRequest,
    fileNames: List<String>,
    totalBytes: Long,
    unreadable: Int,
    progress: ShareProgress,
    onDone: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (share) {
                is ShareRequest.NothingToSend -> {
                    Text("Nothing to send", style = MaterialTheme.typography.titleMedium)
                    Text(share.reason)
                    Button(onClick = onDone) { Text("Close") }
                }
                is ShareRequest.Files -> {
                    Text(
                        if (fileNames.isEmpty()) "Shared files" else "Sharing ${fileNames.size} file(s), ${formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    fileNames.take(5).forEach { Text("• $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (fileNames.size > 5) Text("… and ${fileNames.size - 5} more")
                    if (unreadable > 0) {
                        Text("$unreadable item(s) could not be read and are skipped.", color = MaterialTheme.colorScheme.error)
                    }
                    when (progress) {
                        ShareProgress.Reading -> Text("Reading files…")
                        ShareProgress.NeedsAddress ->
                            Text("Enter the desktop address and tap Connect. The files are sent once connected.")
                        ShareProgress.Connecting -> Text("Connecting to the desktop…")
                        is ShareProgress.CannotSend -> Text(progress.reason, color = MaterialTheme.colorScheme.error)
                        is ShareProgress.Sending -> if (progress.finished) {
                            Text("Finished. See the result below.")
                            Button(onClick = onDone) { Text("Done") }
                        } else {
                            Text("Sending to \"${progress.peerName}\". Keep LinkDrop open until it finishes.")
                        }
                    }
                }
            }
        }
    }
}
