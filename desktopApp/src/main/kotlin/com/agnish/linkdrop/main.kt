package com.agnish.linkdrop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import javax.imageio.ImageIO

fun main() {
    val log = EventLog()
    val local = DesktopDevice.load()
    val receiveDir = DesktopFiles.receiveDir()
    val sink = FolderSink(receiveDir).also { runCatching { it.cleanUpPartials() } }
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
        identity != null -> TransportConfig.Tls()
        else -> null
    }
    val server = transport?.let { HelloServer(local, DEFAULT_PORT, it, hooks, identity, log::add) }
    val startError = if (server == null) {
        "Cannot start: ${identityResult.exceptionOrNull()?.message}"
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

    val icon = appIcon()
    application {
        Window(
            onCloseRequest = {
                announcer.stop()
                server?.stop()
                exitApplication()
            },
            title = "LinkDrop",
            icon = icon,
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

/** The app icon (desktopApp/icons) for the window, or null if it cannot be read. */
private fun appIcon(): Painter? = runCatching {
    DesktopFiles::class.java.getResourceAsStream("/linkdrop.png")?.use { input ->
        ImageIO.read(input)?.let { BitmapPainter(it.toComposeImageBitmap()) }
    }
}.getOrNull()

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

    LinkDropTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("LinkDrop", style = MaterialTheme.typography.headlineSmall)
                    Text("This device: ${local.deviceName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    identity?.let {
                        Text(
                            "Identity: ${Fingerprints.short(it.fingerprint)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (transport == TransportConfig.Plaintext) WarningBanner("Development build: connections are NOT encrypted.")
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                startError != null -> StatusLine(startError, Tone.Error)
                                addresses.isEmpty() -> StatusLine(
                                    "Listening on port $DEFAULT_PORT, but no Wi-Fi/LAN address was found.",
                                    Tone.Error,
                                )
                                else -> {
                                    when (val v = visibility) {
                                        is DesktopAnnouncer.Status.Visible ->
                                            StatusLine("Phones on this Wi-Fi list this desktop as \"${v.name}\".", Tone.Good)
                                        DesktopAnnouncer.Status.Starting ->
                                            StatusLine("Announcing this desktop on the network…", Tone.Neutral)
                                        is DesktopAnnouncer.Status.NotVisible ->
                                            StatusLine("Phones cannot find this desktop automatically (${v.reason}).", Tone.Error)
                                    }
                                    Text("Or enter this address on the phone:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    addresses.forEach {
                                        Text(
                                            "$it:$DEFAULT_PORT",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Received files go to $receiveDir",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenFolder, modifier = Modifier.heightIn(min = BUTTON_HEIGHT)) { Text("Open folder") }
                }

                offers.forEach { OfferCard(it) }

                HorizontalDivider()
                SectionTitle("Phones")
                if (connections.isEmpty()) {
                    Text(
                        "No phone connected. Tap Connect in the LinkDrop app on your phone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                connections.forEach { PhoneCard(it, onSend = { onSendFiles(it) }) }

                PairedPhonesList(pairedPhones)

                if (history.isNotEmpty()) {
                    SectionTitle("Earlier")
                    history.asReversed().forEach { past ->
                        val canRetry = state.retryTarget(past, connections) != null
                        TransferCard(
                            past.peerName, past.progress, onCancel = null,
                            onRetry = if (canRetry) ({ state.retry(past) }) else null,
                            retried = past.retried,
                        )
                    }
                }

                HorizontalDivider()
                SectionTitle("Log")
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
    // On the page background, a step darker than the cards, in a smaller monospace font.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            // Fixed height: a scrolling list cannot sit inside the scrolling page without one.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries) {
                    Text(
                        it,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
