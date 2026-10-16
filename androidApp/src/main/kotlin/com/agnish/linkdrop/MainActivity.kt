package com.agnish.linkdrop

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val connection = (application as LinkDropApplication).connection
        val local = AndroidDevice.load(this)
        val lastAddress = AndroidDevice.lastAddress(this)
        // ALLOW_PLAINTEXT is false in every release build (see androidApp/build.gradle.kts).
        val transport = TransportConfig.choose(BuildConfig.ALLOW_PLAINTEXT, pinnedCertSha256 = null)
        setContent {
            PhoneScreen(local, lastAddress, transport, connection)
        }
    }
}

@Composable
fun PhoneScreen(local: LocalDevice, lastAddress: String, transport: TransportConfig?, connection: PhoneConnection) {
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

    // Remember an address once it worked.
    LaunchedEffect(connected) {
        if (connected != null) AndroidDevice.saveLastAddress(context, addressText.trim())
    }
    KeepScreenOnWhile(transfers.any { !it.finished })

    fun connect() {
        val target = address ?: return
        val secured = transport ?: return
        connection.connect(target, secured)
    }

    // If the user denies the permission we still try: on phones that don't enforce it, it works.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { connect() }

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
                        Button(
                            onClick = {
                                if (needsLocalNetworkPermission(context)) {
                                    permissionLauncher.launch(ACCESS_LOCAL_NETWORK)
                                } else {
                                    connect()
                                }
                            },
                            enabled = transport != null && address != null,
                        ) { Text("Connect") }
                        s.reason?.let { Text("Not connected: $it", color = MaterialTheme.colorScheme.error) }
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
    val activity = LocalContext.current as? Activity ?: return
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
