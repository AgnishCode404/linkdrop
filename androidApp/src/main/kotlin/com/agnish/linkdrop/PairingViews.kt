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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agnish.linkdrop.protocol.Fingerprints
import com.agnish.linkdrop.protocol.Pairing

/** Step 1 of pairing on the phone: compare the code with the desktop's screen. */
@Composable
fun PairingCodeCard(state: PhoneConnection.State.ConfirmPairing) {
    AppCard(modifier = Modifier.fillMaxWidth(), raised = true) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Pairing with a new desktop", style = MaterialTheme.typography.titleLarge)
            Text(state.address.host, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Pairing code", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                Pairing.formatCode(state.code),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Does the desktop show the same code? Only continue if it does.")
            // Large, equal-width buttons; cancel on the left, pair on the right (as on the desktop).
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                DangerButton("No, cancel", onClick = { state.cancel() }, modifier = Modifier.weight(1f), large = true)
                PrimaryButton("Yes, pair", onClick = { state.confirm() }, modifier = Modifier.weight(1f), large = true)
            }
        }
    }
}

/** Step 2: the phone's user confirmed; the desktop's user has to click Allow. */
@Composable
fun WaitingForDesktopCard(state: PhoneConnection.State.WaitingForDesktop, onCancel: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth(), raised = true) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Now click Allow on the desktop", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Code ${Pairing.formatCode(state.code)}",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            DangerButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth(), large = true)
        }
    }
}

/** "Paired desktops": they connect without pairing again. Forget makes the next connection pair. */
@Composable
fun PairedDesktopsList(paired: PairedDesktops) {
    val devices by paired.devices.collectAsState()
    if (devices.isEmpty()) return
    SectionTitle("Paired desktops")
    devices.forEach { device ->
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp).heightIn(min = BUTTON_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("\"${device.name}\"", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Fingerprints.short(device.certSha256),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DangerButton("Forget", onClick = { paired.forget(device.certSha256) })
            }
        }
    }
}
