package com.agnish.linkdrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
        state = rememberDialogState(position = WindowPosition(Alignment.Center), width = 520.dp, height = 460.dp),
        resizable = false,
        alwaysOnTop = true,
    ) {
        ConnectionRequestContent(request)
    }
}

/** The dialog's contents (separate so a test can render them at the dialog's size). */
@Composable
fun ConnectionRequestContent(request: ConnectionRequest) {
    LinkDropTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.padding(24.dp)) {
                // The text takes the remaining space (and scrolls if it ever does not fit, e.g. a
                // long name or large system fonts), so the buttons below can never be pushed out.
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "\"${request.deviceName}\" wants to connect to your device",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("From ${request.address}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val code = request.code
                    if (code != null) {
                        Text(
                            "Pairing code",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            Pairing.formatCode(code),
                            style = MaterialTheme.typography.displaySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text("Allow only if the phone shows the same code. Once allowed, it can connect without asking.")
                    } else {
                        WarningBanner(
                            "Development build: this connection is NOT encrypted and cannot be verified. " +
                                "It is not remembered.",
                        )
                    }
                    Text(
                        "Declined automatically in ${Pairing.CONFIRM_TIMEOUT_SECONDS / 60} minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                // Large, equal-width buttons that are easy to hit; Decline on the left, Allow on the right.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    DangerButton("Decline", onClick = { request.decline() }, modifier = Modifier.weight(1f), large = true)
                    PrimaryButton("Allow", onClick = { request.allow() }, modifier = Modifier.weight(1f), large = true)
                }
            }
        }
    }
}

/** "Paired phones": they connect without asking. Forget makes the next connection ask again. */
@Composable
fun PairedPhonesList(pairedPhones: PairedPhones) {
    val devices by pairedPhones.devices.collectAsState()
    SectionTitle("Paired phones")
    if (devices.isEmpty()) {
        Text(
            "None yet. A phone is paired the first time you allow it to connect.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    devices.forEach { device ->
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
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
                DangerButton("Forget", onClick = { pairedPhones.forget(device.certSha256) })
            }
        }
    }
}
