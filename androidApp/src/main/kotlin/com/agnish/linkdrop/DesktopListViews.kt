package com.agnish.linkdrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agnish.linkdrop.protocol.DiscoveredDesktop

/** "Desktops on this Wi-Fi": what [DesktopFinder] found. Tapping one connects to it. */
@Composable
fun DesktopList(
    desktops: List<DiscoveredDesktop>,
    lastDesktopId: String?,
    problem: String?,
    enabled: Boolean,
    onPick: (DiscoveredDesktop) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Desktops on this Wi-Fi", style = MaterialTheme.typography.titleMedium)
        when {
            problem != null -> Text(problem, color = MaterialTheme.colorScheme.error)
            desktops.isEmpty() -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Searching… Make sure LinkDrop is open on the desktop, on the same Wi-Fi.")
            }
            else -> desktops.forEach { desktop ->
                OutlinedCard(onClick = { onPick(desktop) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                desktop.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (desktop.deviceId == lastDesktopId) {
                                Text("last used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        // The address is shown so the user can spot an unexpected one (announcements
                        // are not authenticated until pairing exists).
                        Text("${desktop.address.host}:${desktop.address.port}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
