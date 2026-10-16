package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.FEATURE_TRANSFER
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.MAX_DEVICE_FIELD_LENGTH
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.prefs.Preferences
import kotlin.uuid.Uuid

object DesktopDevice {
    private const val PREFS_NODE = "com/agnish/linkdrop"
    private const val KEY_DEVICE_ID = "deviceId"

    /** This desktop's identity. The ID is random, created on first run and then kept. */
    fun load(): LocalDevice =
        LocalDevice(deviceId = loadOrCreateDeviceId(), deviceName = deviceName(), features = listOf(FEATURE_TRANSFER))

    private fun loadOrCreateDeviceId(): String {
        val prefs = Preferences.userRoot().node(PREFS_NODE)
        prefs.get(KEY_DEVICE_ID, null)?.let { return it }
        val id = Uuid.random().toString()
        prefs.put(KEY_DEVICE_ID, id)
        prefs.flush()
        return id
    }

    private fun deviceName(): String {
        val hostname = runCatching { File("/etc/hostname").readText().trim() }.getOrNull()
        val clean = hostname?.filterNot { it.isISOControl() }?.take(MAX_DEVICE_FIELD_LENGTH)
        return if (clean.isNullOrBlank()) "Linux desktop" else clean
    }

    /** IPv4 addresses on the local network (e.g. 192.168.x.x) that a phone could connect to. */
    fun lanAddresses(): List<String> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
}
