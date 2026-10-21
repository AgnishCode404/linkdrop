package com.agnish.linkdrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
    /** Device ids of paired desktops: these are verified by certificate when connecting. */
    pairedIds: Set<String>,
    problem: String?,
    enabled: Boolean,
    onPick: (DiscoveredDesktop) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Desktops on this Wi-Fi")
        when {
            problem != null -> StatusLine(problem, Tone.Error)
            desktops.isEmpty() -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    "Searching… Make sure LinkDrop is open on the desktop, on the same Wi-Fi.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> desktops.forEach { desktop ->
                // The whole row is the tap target, well above 48dp.
                AppCard(
                    onClick = { onPick(desktop) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                desktop.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (desktop.deviceId in pairedIds) Tag("Paired", MaterialTheme.colorScheme.primary)
                            if (desktop.deviceId == lastDesktopId) Tag("Last used", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // The address is shown so the user can spot an unexpected one: announcements
                        // are not authenticated; only pairing (the certificate) proves who a desktop is.
                        Text(
                            "${desktop.address.host}:${desktop.address.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
