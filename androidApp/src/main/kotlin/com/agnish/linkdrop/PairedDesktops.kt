package com.agnish.linkdrop

import android.content.Context
import com.agnish.linkdrop.protocol.PairedDevice
import com.agnish.linkdrop.protocol.PairedDevices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The desktops this phone has paired with (same rules and format as the desktop's paired phones).
 * [load]/[save] hold the encoded list; [fromPreferences] keeps it in the app's private storage.
 */
class PairedDesktops(private val load: () -> String?, private val save: (String) -> Unit) {
    private val paired: PairedDevices = load()?.let { PairedDevices.decode(it) } ?: PairedDevices()
    private val _devices = MutableStateFlow(paired.all)
    val devices: StateFlow<List<PairedDevice>> = _devices.asStateFlow()

    @Synchronized
    fun byFingerprint(certSha256: String): PairedDevice? = paired.all.firstOrNull { it.certSha256 == certSha256 }

    @Synchronized
    fun byDeviceId(deviceId: String): PairedDevice? = paired.byDeviceId(deviceId)

    @Synchronized
    fun add(device: PairedDevice) = change { paired.add(device) }

    @Synchronized
    fun rename(certSha256: String, name: String) {
        if (byFingerprint(certSha256)?.name?.let { it != name } == true) change { paired.rename(certSha256, name) }
    }

    @Synchronized
    fun forget(certSha256: String) = change { paired.forget(certSha256) }

    private fun change(edit: () -> Unit) {
        edit()
        save(paired.encode())
        _devices.value = paired.all
    }

    companion object {
        private const val PREFS = "linkdrop-paired"
        private const val KEY = "desktops"

        /** Private app storage (not backed up: allowBackup is false). */
        fun fromPreferences(context: Context): PairedDesktops {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return PairedDesktops(load = { prefs.getString(KEY, null) }, save = { prefs.edit().putString(KEY, it).apply() })
        }
    }
}
