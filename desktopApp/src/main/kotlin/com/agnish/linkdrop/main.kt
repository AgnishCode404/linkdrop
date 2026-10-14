package com.agnish.linkdrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.agnish.linkdrop.protocol.DEFAULT_PORT
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.TransportConfig

fun main() {
    val log = EventLog()
    val local = DesktopDevice.load()
    // BuildFlags is generated from linkdrop.allowPlaintext (see desktopApp/build.gradle.kts).
    val transport = TransportConfig.choose(BuildFlags.ALLOW_PLAINTEXT, pinnedCertSha256 = null)
    val server = transport?.let { HelloServer(local, DEFAULT_PORT, it, log::add) }
    val startError = if (server == null) {
        TransportConfig.NO_TRANSPORT_MESSAGE
    } else {
        runCatching { server.start() }.exceptionOrNull()
            ?.let { "Server failed to start on port $DEFAULT_PORT: ${it.message}" }
    }
    if (server != null && startError == null) log.add("Server started on port $DEFAULT_PORT")
    val addresses = DesktopDevice.lanAddresses()

    application {
        Window(
            onCloseRequest = {
                server?.stop()
                exitApplication()
            },
            title = "LinkDrop",
        ) {
            DesktopScreen(local, addresses, transport, startError, log)
        }
    }
}

@Composable
fun DesktopScreen(
    local: LocalDevice,
    addresses: List<String>,
    transport: TransportConfig?,
    startError: String?,
    log: EventLog,
) {
    val entries by log.entries.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("LinkDrop", style = MaterialTheme.typography.headlineSmall)
                Text("This device: ${local.deviceName}")
                if (transport == TransportConfig.Plaintext) {
                    Text(
                        "Development build: connections are NOT encrypted.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SelectionContainer {
                    Column {
                        when {
                            startError != null -> Text(startError, color = MaterialTheme.colorScheme.error)
                            addresses.isEmpty() -> Text(
                                "Listening on port $DEFAULT_PORT, but no Wi-Fi/LAN address was found.",
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> {
                                Text("Enter this address on the phone:")
                                addresses.forEach {
                                    Text("$it:$DEFAULT_PORT", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("Log", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                        items(entries) { Text(it, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }
    }
}
