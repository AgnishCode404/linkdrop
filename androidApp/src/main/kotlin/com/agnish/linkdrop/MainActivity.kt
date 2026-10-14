package com.agnish.linkdrop

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.agnish.linkdrop.protocol.HelloResult
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.ServerAddress
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

        val local = AndroidDevice.load(this)
        val lastAddress = AndroidDevice.lastAddress(this)
        setContent {
            HelloScreen(local, lastAddress)
        }
    }
}

private sealed interface Status {
    data object Idle : Status
    data object Connecting : Status
    data class Done(val result: HelloResult) : Status
}

@Composable
fun HelloScreen(local: LocalDevice, lastAddress: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addressText by rememberSaveable { mutableStateOf(lastAddress) }
    var status by remember { mutableStateOf<Status>(Status.Idle) }
    val address = ServerAddress.parse(addressText)

    fun connect(target: ServerAddress) {
        status = Status.Connecting
        val typed = addressText.trim()
        scope.launch {
            val result = HelloClient.sayHello(target, local)
            if (result is HelloResult.Connected) AndroidDevice.saveLastAddress(context, typed)
            status = Status.Done(result)
        }
    }

    // If the user denies the permission we still try: on phones that don't enforce it, it works.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        address?.let(::connect)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.safeContentPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("LinkDrop", style = MaterialTheme.typography.headlineSmall)
                Text("This device: ${local.deviceName}")
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
                        val target = address ?: return@Button
                        if (needsLocalNetworkPermission(context)) {
                            permissionLauncher.launch(ACCESS_LOCAL_NETWORK)
                        } else {
                            connect(target)
                        }
                    },
                    enabled = address != null && status != Status.Connecting,
                ) {
                    Text("Say hello")
                }
                when (val s = status) {
                    Status.Idle -> Unit
                    Status.Connecting -> Text("Connecting…")
                    is Status.Done -> when (val result = s.result) {
                        is HelloResult.Connected -> Text(
                            "Connected to \"${result.serverName}\"",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        is HelloResult.Failed -> Text(
                            "Failed: ${result.reason}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
