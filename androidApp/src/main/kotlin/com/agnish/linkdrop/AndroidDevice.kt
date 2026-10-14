package com.agnish.linkdrop

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.MAX_DEVICE_FIELD_LENGTH
import kotlin.uuid.Uuid

object AndroidDevice {
    private const val PREFS = "linkdrop"
    private const val KEY_DEVICE_ID = "deviceId"
    private const val KEY_LAST_ADDRESS = "lastAddress"

    /** This phone's identity. The ID is random, created on first run and then kept. */
    fun load(context: Context): LocalDevice =
        LocalDevice(deviceId = loadOrCreateDeviceId(context), deviceName = deviceName(context))

    fun lastAddress(context: Context): String = prefs(context).getString(KEY_LAST_ADDRESS, "") ?: ""

    fun saveLastAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_LAST_ADDRESS, address).apply()
    }

    private fun loadOrCreateDeviceId(context: Context): String {
        prefs(context).getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = Uuid.random().toString()
        prefs(context).edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** The name the user gave the phone in Settings, or its model name. */
    private fun deviceName(context: Context): String {
        val userName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } else {
            null
        }
        val clean = (userName ?: Build.MODEL)?.filterNot { it.isISOControl() }?.trim()?.take(MAX_DEVICE_FIELD_LENGTH)
        return if (clean.isNullOrBlank()) "Android phone" else clean
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
