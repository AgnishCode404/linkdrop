package com.agnish.linkdrop

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.ServerAddress
import com.agnish.linkdrop.protocol.TransferLimits
import com.agnish.linkdrop.protocol.TransportConfig
import com.agnish.linkdrop.transfer.TransferProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Android 17 adds a runtime permission for connecting to devices on the local network. Whether
 * it is enforced depends on the phone's OS build, so we ask for it only when it is not granted.
 */
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

private fun needsLocalNetworkPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
        context.checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

class MainActivity : ComponentActivity() {
    private lateinit var finder: DesktopFinder

    /**
     * Searching waits until the local network permission question is answered: without the
     * permission, Android 17 shows its own device picker, which must not appear on top of it.
     */
    private var discoveryAllowed = false
    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        discoveryAllowed = true
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) finder.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val connection = (application as LinkDropApplication).connection
        val local = AndroidDevice.load(this)
        val lastAddress = AndroidDevice.lastAddress(this)
        // ALLOW_PLAINTEXT is false in every release build (see androidApp/build.gradle.kts).
        val transport = TransportConfig.choose(BuildConfig.ALLOW_PLAINTEXT, pinnedCertSha256 = null)
        // Non-null when opened from another app's share menu.
        val share = ShareRequest.from(intent)

        finder = DesktopFinder(this)
        // Ask once when the app opens (not again after rotation); discovery is the main way to connect.
        discoveryAllowed = !needsLocalNetworkPermission(this) || savedInstanceState != null
        if (!discoveryAllowed) askLocalNetwork.launch(ACCESS_LOCAL_NETWORK)

        setContent {
            PhoneScreen(
                local, lastAddress, transport, connection, finder, AndroidDevice.lastDesktopId(this),
                share, onShareDone = { finish() },
            )
        }
    }

    // Search only while the screen is visible.
    override fun onStart() {
        super.onStart()
        if (discoveryAllowed) finder.start()
    }

    override fun onStop() {
        finder.stop()
        super.onStop()
    }
}

@Composable
fun PhoneScreen(
    local: LocalDevice,
    lastAddress: String,
    transport: TransportConfig?,
    connection: PhoneConnection,
    finder: DesktopFinder,
    lastDesktopId: String?,
    share: ShareRequest? = null,
    onShareDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by connection.state.collectAsState()
    val offers by connection.offers.collectAsState()
    val history by connection.history.collectAsState()
    var addressText by rememberSaveable { mutableStateOf(lastAddress) }
    var problem by remember { mutableStateOf<String?>(null) }
    val address = ServerAddress.parse(addressText)

    val connected = state as? PhoneConnection.State.Connected
    val noTransfers = remember { MutableStateFlow(emptyList<TransferProgress>()) }
    val transfers by (connected?.session?.transfers ?: noTransfers).collectAsState()

    val desktops by finder.desktops.collectAsState()
    val finderProblem by finder.problem.collectAsState()
    var manualAddress by rememberSaveable { mutableStateOf(false) }

    // Remember the desktop once connected: its address (sharing connects there) and its id.
    LaunchedEffect(connected) {
        if (connected != null) {
            val target = "${connected.address.host}:${connected.address.port}"
            AndroidDevice.saveLastDesktop(context, target, connected.peerId)
        }
    }
    KeepScreenOnWhile(transfers.any { !it.finished })

    fun connect(target: ServerAddress) {
        val secured = transport ?: return
        connection.connect(target, secured)
    }

    // If the user denies the permission we still try: on phones that don't enforce it, it works.
    var pendingTarget by remember { mutableStateOf<ServerAddress?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingTarget?.let(::connect)
    }

    fun requestConnect(target: ServerAddress?) {
        if (target == null) return
        if (needsLocalNetworkPermission(context)) {
            pendingTarget = target
            permissionLauncher.launch(ACCESS_LOCAL_NETWORK)
        } else {
            connect(target)
        }
    }

    // ---- Files shared from another app: connect to the last desktop and send them, once. ----
    // Saved across rotation, so turning the phone never sends the same files twice.
    var shareConnectTried by rememberSaveable { mutableStateOf(false) }
    var shareTransferIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var shareSources by remember { mutableStateOf<List<UriSource>?>(null) }
    var shareUnreadable by remember { mutableStateOf(0) }

    LaunchedEffect(share) {
        if (share !is ShareRequest.Files) return@LaunchedEffect
        val results = share.uris.map { runCatching { UriSource.from(context, it) } }
        shareSources = results.mapNotNull { it.getOrNull() }
        shareUnreadable = share.skipped + results.count { it.isFailure }
    }
    LaunchedEffect(share, state, shareSources) {
        if (share !is ShareRequest.Files || shareTransferIds.isNotEmpty()) return@LaunchedEffect
        val sources = shareSources ?: return@LaunchedEffect
        when (val s = state) {
            // Only the last address actually used, never a discovered one: an announcement is not
            // authenticated, and following a desktop id to a new address would let anyone on the
            // Wi-Fi who copies that id receive shared files without a tap (until pairing exists).
            is PhoneConnection.State.Disconnected -> if (!shareConnectTried && address != null && transport != null) {
                shareConnectTried = true
                requestConnect(address)
            }
            is PhoneConnection.State.Connected -> if (s.transferEnabled && sources.isNotEmpty()) {
                val ids = sources.chunked(TransferLimits.MAX_FILES_PER_OFFER).map { s.session.send(it) }
                shareTransferIds = ArrayList(ids)
            }
            is PhoneConnection.State.Connecting -> Unit
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val session = connected?.session ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val failed = mutableListOf<String>()
            val sources = uris.mapNotNull { uri ->
                runCatching { UriSource.from(context, uri) }.onFailure { failed += uri.toString() }.getOrNull()
            }
            problem = if (failed.isEmpty()) null else "Could not read ${failed.size} file(s)"
            sources.chunked(TransferLimits.MAX_FILES_PER_OFFER).forEach { session.send(it) }
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.safeContentPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("LinkDrop", style = MaterialTheme.typography.headlineSmall)
                Text("This device: ${local.deviceName}")
                when (transport) {
                    null -> Text(TransportConfig.NO_TRANSPORT_MESSAGE, color = MaterialTheme.colorScheme.error)
                    TransportConfig.Plaintext -> Text(
                        "Development build: connections are NOT encrypted.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    is TransportConfig.Tls -> Unit
                }

                when (val s = state) {
                    is PhoneConnection.State.Disconnected -> {
                        s.reason?.let { Text("Not connected: $it", color = MaterialTheme.colorScheme.error) }
                        DesktopList(
                            desktops = desktops,
                            lastDesktopId = lastDesktopId,
                            problem = finderProblem,
                            enabled = transport != null,
                            onPick = { desktop ->
                                addressText = "${desktop.address.host}:${desktop.address.port}"
                                requestConnect(desktop.address)
                            },
                        )
                        TextButton(onClick = { manualAddress = !manualAddress }) {
                            Text(if (manualAddress) "Hide manual address" else "Enter address manually")
                        }
                        if (manualAddress) {
                            OutlinedTextField(
                                value = addressText,
                                onValueChange = { addressText = it },
                                label = { Text("Desktop address") },
                                placeholder = { Text("192.168.1.20:47800") },
                                isError = addressText.isNotBlank() && address == null,
                                supportingText = { Text("Shown in the desktop app's window") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(onClick = { requestConnect(address) }, enabled = transport != null && address != null) {
                                Text("Connect")
                            }
                        }
                    }
                    is PhoneConnection.State.Connecting -> {
                        Text("Connecting to ${s.address.host}:${s.address.port}…")
                        OutlinedButton(onClick = { connection.disconnect() }) { Text("Cancel") }
                    }
                    is PhoneConnection.State.Connected -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Connected to \"${s.peerName}\"",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { connection.disconnect() }) { Text("Disconnect") }
                        }
                        if (s.transferEnabled) {
                            Button(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Send files") }
                            Text(
                                "Keep LinkDrop open while files transfer.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text(
                                "The desktop's LinkDrop app is too old to transfer files.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                if (share != null) {
                    val sources = shareSources
                    val progress = when {
                        shareTransferIds.isNotEmpty() -> ShareProgress.Sending(
                            peerName = connected?.peerName ?: "desktop",
                            finished = shareTransferIds.all { id ->
                                (transfers + history).firstOrNull { it.transferId == id }?.finished ?: false
                            },
                        )
                        sources == null -> ShareProgress.Reading
                        sources.isEmpty() -> ShareProgress.CannotSend("None of the shared files could be read.")
                        connected != null && !connected.transferEnabled ->
                            ShareProgress.CannotSend("The desktop's LinkDrop app is too old to transfer files.")
                        state is PhoneConnection.State.Connecting -> ShareProgress.Connecting
                        else -> ShareProgress.NeedsAddress
                    }
                    ShareCard(
                        share = share,
                        fileNames = sources?.map { it.name }.orEmpty(),
                        totalBytes = sources?.sumOf { it.size } ?: 0L,
                        unreadable = shareUnreadable,
                        progress = progress,
                        onDone = onShareDone,
                    )
                }

                offers.forEach { OfferCard(it) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Received files go to $RECEIVE_FOLDER", modifier = Modifier.weight(1f))
                    TextButton(onClick = { openDownloads(context) }) { Text("Open") }
                }

                val peerName = connected?.peerName ?: "desktop"
                if (transfers.isNotEmpty() || history.isNotEmpty()) HorizontalDivider()
                transfers.asReversed().forEach { t ->
                    TransferCard(peerName, t, onCancel = { connected?.session?.cancel(t.transferId) })
                }
                history.asReversed().forEach { TransferCard("desktop", it, onCancel = null) }
            }
        }
    }
}

/** The app must stay open during transfers (no background service yet), so keep the screen on. */
@Composable
private fun KeepScreenOnWhile(active: Boolean) {
    // LocalActivity, not LocalContext: the Compose context may be a wrapper around the activity.
    val activity = LocalActivity.current ?: return
    DisposableEffect(active) {
        if (active) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private fun openDownloads(context: Context) {
    runCatching {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
