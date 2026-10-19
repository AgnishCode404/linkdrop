package com.agnish.linkdrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pairing with a new desktop", style = MaterialTheme.typography.titleMedium)
            Text(state.address.host, style = MaterialTheme.typography.bodySmall)
            Text(
                Pairing.formatCode(state.code),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text("Does the desktop show the same code? Only continue if it does.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { state.cancel() }) { Text("No, cancel") }
                Button(onClick = { state.confirm() }) { Text("Yes, pair") }
            }
        }
    }
}

/** Step 2: the phone's user confirmed; the desktop's user has to click Allow. */
@Composable
fun WaitingForDesktopCard(state: PhoneConnection.State.WaitingForDesktop, onCancel: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Now click Allow on the desktop", style = MaterialTheme.typography.titleMedium)
            Text(
                "Code ${Pairing.formatCode(state.code)}",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
            )
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** "Paired desktops": they connect without pairing again. Forget makes the next connection pair. */
@Composable
fun PairedDesktopsList(paired: PairedDesktops) {
    val devices by paired.devices.collectAsState()
    if (devices.isEmpty()) return
    Text("Paired desktops", style = MaterialTheme.typography.titleMedium)
    devices.forEach { device ->
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("\"${device.name}\"", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Fingerprints.short(device.certSha256),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = { paired.forget(device.certSha256) }) { Text("Forget") }
            }
        }
    }
}
