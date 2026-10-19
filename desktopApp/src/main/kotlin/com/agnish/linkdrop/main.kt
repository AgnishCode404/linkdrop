package com.agnish.linkdrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.agnish.linkdrop.protocol.DEFAULT_PORT
import com.agnish.linkdrop.protocol.Fingerprints
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.TransferLimits
import com.agnish.linkdrop.protocol.TransportConfig
import kotlinx.coroutines.flow.StateFlow
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val log = EventLog()
    val local = DesktopDevice.load()
    val receiveDir = DesktopFiles.receiveDir()
    val sink = FolderSink(receiveDir).also { runCatching { it.deleteLeftovers() } }
    val state = DesktopState()

    // This desktop's TLS identity and the phones paired with it (~/.local/share/linkdrop).
    val dataDir = linkDropDataDir()
    val identityResult = runCatching { DesktopIdentity.loadOrCreate(dataDir, log::add) }
    val identity = identityResult.getOrNull()
    val pairedPhones = PairedPhones(dataDir.resolve(PairedPhones.FILE_NAME), log::add)
    val hooks = ServerHooks(
        sink, state.decider, state::connected, state::disconnected,
        approver = state.approver,
        pairedPhones = pairedPhones,
    )

    // BuildFlags is generated from linkdrop.allowPlaintext (see desktopApp/build.gradle.kts).
    // TLS whenever plaintext is not explicitly allowed; never a fallback from one to the other.
    val transport = when {
        BuildFlags.ALLOW_PLAINTEXT -> TransportConfig.Plaintext
        identity != null -> TransportConfig.Tls(identity.fingerprint)
        else -> null
    }
    val server = transport?.let { HelloServer(local, DEFAULT_PORT, it, hooks, identity, log::add) }
    val startError = if (server == null) {
        "Cannot start: ${identityResult.exceptionOrNull()?.message ?: TransportConfig.NO_TRANSPORT_MESSAGE}"
    } else {
        runCatching { server.start() }.exceptionOrNull()
            ?.let { "Server failed to start on port $DEFAULT_PORT: ${it.message}" }
    }
    if (server != null && startError == null) log.add("Server started on port $DEFAULT_PORT")
    val addresses = DesktopDevice.lanAddresses()

    // Announce on the network only when the server really runs; mDNS takes a few seconds.
    val announcer = DesktopAnnouncer(local, DEFAULT_PORT, log::add)
    if (server != null && startError == null) {
        Thread({ announcer.start(addresses) }, "mdns-announce").apply { isDaemon = true }.start()
    }

    application {
        Window(
            onCloseRequest = {
                announcer.stop()
                server?.stop()
                exitApplication()
            },
            title = "LinkDrop",
            state = rememberWindowState(width = 760.dp, height = 820.dp),
        ) {
            // Bring the window forward when a phone wants to send something or to connect.
            val offers by state.offers.collectAsState()
            LaunchedEffect(offers.size) { if (offers.isNotEmpty()) window.toFront() }
            val request by state.request.collectAsState()
            LaunchedEffect(request) { if (request != null) window.toFront() }

            DesktopScreen(
                local, addresses, transport, startError, receiveDir, state, log, announcer.status,
                identity, pairedPhones,
                onSendFiles = { connection -> sendPickedFiles(window, connection, log) },
                onOpenFolder = { openFolder(receiveDir, log) },
            )
        }

        // "<name> wants to connect to your device": one at a time, centered, above other windows.
        val request by state.request.collectAsState()
        request?.let { pending -> key(pending) { ConnectionRequestDialog(pending) } }
    }
}

/** Lets the user pick files (system file chooser) and queues them for [connection]. */
private fun sendPickedFiles(parent: Frame, connection: PeerConnection, log: EventLog) {
    val dialog = FileDialog(parent, "Choose files to send to \"${connection.name}\"", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true // blocks until the user closes the dialog
    val sources = dialog.files.mapNotNull { file ->
        val path = file.toPath()
        if (!Files.isRegularFile(path)) return@mapNotNull null
        runCatching { PathSource(path) }.onFailure { log.add("Cannot read $path: ${it.message}") }.getOrNull()
    }
    // One offer holds at most MAX_FILES_PER_OFFER files; larger selections become several queued offers.
    sources.chunked(TransferLimits.MAX_FILES_PER_OFFER).forEach { connection.session.send(it) }
}

private fun openFolder(dir: Path, log: EventLog) {
    runCatching {
        Files.createDirectories(dir)
        ProcessBuilder("xdg-open", dir.toString()).start()
    }.onFailure { log.add("Could not open $dir: ${it.message}") }
}

@Composable
fun DesktopScreen(
    local: LocalDevice,
    addresses: List<String>,
    transport: TransportConfig?,
    startError: String?,
    receiveDir: Path,
    state: DesktopState,
    log: EventLog,
    announcement: StateFlow<DesktopAnnouncer.Status>,
    identity: DesktopIdentity?,
    pairedPhones: PairedPhones,
    onSendFiles: (PeerConnection) -> Unit,
    onOpenFolder: () -> Unit,
) {
    val visibility by announcement.collectAsState()
    val connections by state.connections.collectAsState()
    val offers by state.offers.collectAsState()
    val history by state.history.collectAsState()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("LinkDrop", style = MaterialTheme.typography.headlineSmall)
                Text("This device: ${local.deviceName}")
                identity?.let {
                    Text(
                        "Identity: ${Fingerprints.short(it.fingerprint)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
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
                                when (val v = visibility) {
                                    is DesktopAnnouncer.Status.Visible -> Text(
                                        "Phones on this Wi-Fi list this desktop as \"${v.name}\".",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    DesktopAnnouncer.Status.Starting -> Text("Announcing this desktop on the network…")
                                    is DesktopAnnouncer.Status.NotVisible -> Text(
                                        "Phones cannot find this desktop automatically (${v.reason}).",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Text("Or enter this address on the phone:")
                                addresses.forEach {
                                    Text("$it:$DEFAULT_PORT", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Received files go to $receiveDir", modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenFolder) { Text("Open folder") }
                }

                offers.forEach { OfferCard(it) }

                HorizontalDivider()
                Text("Phones", style = MaterialTheme.typography.titleMedium)
                if (connections.isEmpty()) {
                    Text("No phone connected. Tap Connect in the LinkDrop app on your phone.")
                }
                connections.forEach { PhoneCard(it, onSend = { onSendFiles(it) }) }

                PairedPhonesList(pairedPhones)

                if (history.isNotEmpty()) {
                    Text("Earlier", style = MaterialTheme.typography.titleMedium)
                    history.asReversed().forEach { TransferCard(it.peerName, it.progress, onCancel = null) }
                }

                HorizontalDivider()
                Text("Log", style = MaterialTheme.typography.titleMedium)
                LogView(log)
            }
        }
    }
}

@Composable
private fun LogView(log: EventLog) {
    val entries by log.entries.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }
    SelectionContainer {
        // Fixed height: a scrolling list cannot sit inside the scrolling page without one.
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(180.dp)) {
            items(entries) { Text(it, fontFamily = FontFamily.Monospace) }
        }
    }
}
