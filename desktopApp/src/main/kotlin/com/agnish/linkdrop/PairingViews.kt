package com.agnish.linkdrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.agnish.linkdrop.protocol.Fingerprints
import com.agnish.linkdrop.protocol.Pairing

/**
 * "<name> wants to connect to your device", centered on the screen and above other windows.
 * Closing it counts as Decline.
 */
@Composable
fun ConnectionRequestDialog(request: ConnectionRequest) {
    DialogWindow(
        onCloseRequest = { request.decline() },
        title = "LinkDrop: connection request",
        state = rememberDialogState(position = WindowPosition(Alignment.Center), width = 480.dp, height = 340.dp),
        resizable = false,
        alwaysOnTop = true,
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "\"${request.deviceName}\" wants to connect to your device",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("From ${request.address}", style = MaterialTheme.typography.bodyMedium)
                    val code = request.code
                    if (code != null) {
                        Text("Pairing code", style = MaterialTheme.typography.labelLarge)
                        Text(
                            Pairing.formatCode(code),
                            style = MaterialTheme.typography.displaySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("Allow only if the phone shows the same code. Once allowed, it can connect without asking.")
                    } else {
                        Text(
                            "Development build: this connection is NOT encrypted and cannot be verified. " +
                                "It is not remembered.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Declined automatically in ${Pairing.CONFIRM_TIMEOUT_SECONDS / 60} minutes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(onClick = { request.decline() }) { Text("Decline") }
                        Button(onClick = { request.allow() }) { Text("Allow") }
                    }
                }
            }
        }
    }
}

/** "Paired phones": they connect without asking. Forget makes the next connection ask again. */
@Composable
fun PairedPhonesList(pairedPhones: PairedPhones) {
    val devices by pairedPhones.devices.collectAsState()
    Text("Paired phones", style = MaterialTheme.typography.titleMedium)
    if (devices.isEmpty()) {
        Text("None yet. A phone is paired the first time you allow it to connect.")
        return
    }
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
                TextButton(onClick = { pairedPhones.forget(device.certSha256) }) { Text("Forget") }
            }
        }
    }
}
