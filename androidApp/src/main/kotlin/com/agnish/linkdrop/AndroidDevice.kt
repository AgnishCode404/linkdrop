package com.agnish.linkdrop

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.agnish.linkdrop.protocol.FEATURE_TRANSFER
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.MAX_DEVICE_FIELD_LENGTH
import kotlin.uuid.Uuid

object AndroidDevice {
    private const val PREFS = "linkdrop"
    private const val KEY_DEVICE_ID = "deviceId"
    private const val KEY_LAST_ADDRESS = "lastAddress"
    private const val KEY_LAST_DESKTOP_ID = "lastDesktopId"

    /** This phone's identity. The ID is random, created on first run and then kept. */
    fun load(context: Context): LocalDevice = LocalDevice(
        deviceId = loadOrCreateDeviceId(context),
        deviceName = deviceName(context),
        features = listOf(FEATURE_TRANSFER),
    )

    fun lastAddress(context: Context): String = prefs(context).getString(KEY_LAST_ADDRESS, "") ?: ""

    /** The desktop's device id, to mark it "last used" in the list of discovered desktops. */
    fun lastDesktopId(context: Context): String? = prefs(context).getString(KEY_LAST_DESKTOP_ID, null)

    /** Remembers the desktop just connected to: its address (for sharing) and its id. */
    fun saveLastDesktop(context: Context, address: String, desktopId: String) {
        prefs(context).edit().putString(KEY_LAST_ADDRESS, address).putString(KEY_LAST_DESKTOP_ID, desktopId).apply()
    }

    private fun loadOrCreateDeviceId(context: Context): String {
        prefs(context).getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = Uuid.random().toString()
        prefs(context).edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** The name the user gave the phone in Settings, or its model name. */
    private fun deviceName(context: Context): String {
        val userName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        val clean = (userName ?: Build.MODEL)?.filterNot { it.isISOControl() }?.trim()?.take(MAX_DEVICE_FIELD_LENGTH)
        return if (clean.isNullOrBlank()) "Android phone" else clean
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
