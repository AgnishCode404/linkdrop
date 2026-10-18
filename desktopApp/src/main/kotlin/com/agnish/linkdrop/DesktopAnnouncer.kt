package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.DiscoveredDesktop
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.SERVICE_TYPE_FQDN
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Announces this desktop on the local network (mDNS / DNS-SD, PROTOCOL.md "Discovery") so
 * phones can list it instead of the user typing its address.
 */
class DesktopAnnouncer(
    private val local: LocalDevice,
    private val port: Int,
    private val log: (String) -> Unit,
) {
    sealed interface Status {
        data object Starting : Status
        /** [addresses]: where the announcement went out. */
        data class Visible(val name: String, val addresses: List<String>) : Status
        data class NotVisible(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Starting)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val running = mutableListOf<JmDNS>()

    /**
     * Announces on each of [addresses] (one JmDNS per network interface address). Blocks for a
     * few seconds while mDNS checks that the name is free, so call it off the UI thread.
     */
    @Synchronized
    fun start(addresses: List<String>) {
        if (addresses.isEmpty()) {
            _status.value = Status.NotVisible("no Wi-Fi/LAN address")
            return
        }
        val announced = mutableListOf<String>()
        var name = local.deviceName
        for (address in addresses) {
            try {
                // An mDNS host name must not contain spaces etc., unlike the display name.
                val jmdns = JmDNS.create(InetAddress.getByName(address), hostName())
                val info = ServiceInfo.create(SERVICE_TYPE_FQDN, local.deviceName, port, 0, 0, DiscoveredDesktop.txtRecord(local))
                jmdns.registerService(info)
                running += jmdns
                announced += address
                // mDNS renames the instance (e.g. "Arch (2)") if another device already uses the name.
                name = info.name
            } catch (e: Exception) {
                log("Could not announce on $address: ${e.message}")
            }
        }
        _status.value = if (announced.isEmpty()) {
            Status.NotVisible("announcing failed (see log)")
        } else {
            log("Visible to phones as \"$name\" on ${announced.joinToString()}")
            Status.Visible(name, announced)
        }
    }

    /** Withdraws the announcement (phones see the desktop disappear). Takes up to a second or two. */
    @Synchronized
    fun stop() {
        running.forEach { jmdns ->
            runCatching {
                jmdns.unregisterAllServices()
                jmdns.close()
            }
        }
        running.clear()
        _status.value = Status.NotVisible("stopped")
    }

    /** e.g. `linkdrop-3f2a9c1e`: stable for this desktop, valid as an mDNS host name. */
    private fun hostName(): String =
        "linkdrop-" + local.deviceId.filter { it.isLetterOrDigit() }.take(8).lowercase()
}
