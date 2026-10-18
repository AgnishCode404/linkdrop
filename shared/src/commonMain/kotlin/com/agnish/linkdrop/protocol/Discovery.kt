package com.agnish.linkdrop.protocol

/**
 * DNS-SD service type the desktop announces over mDNS (PROTOCOL.md, "Discovery"). Android's
 * NsdManager uses it as is; JmDNS wants it fully qualified ([SERVICE_TYPE_FQDN]).
 */
const val SERVICE_TYPE = "_linkdrop._tcp"
const val SERVICE_TYPE_FQDN = "$SERVICE_TYPE.local."

/** Keys of the announcement's TXT record. */
object TxtKeys {
    /** [PROTOCOL_VERSION] of the announcing desktop. */
    const val VERSION = "v"

    /** The desktop's permanent random device id (the same one it sends in `helloAck`). */
    const val DEVICE_ID = "id"
}

/**
 * A desktop found on the network. Everything here comes from an unauthenticated announcement:
 * anyone on the Wi-Fi can announce anything, so [fromAnnouncement] validates it all.
 */
data class DiscoveredDesktop(val deviceId: String, val name: String, val address: ServerAddress) {
    companion object {
        /** Shown when an announcement has no usable name. */
        const val UNNAMED = "LinkDrop desktop"

        /** The TXT record a desktop announces for [local]. */
        fun txtRecord(local: LocalDevice): Map<String, String> =
            mapOf(TxtKeys.VERSION to PROTOCOL_VERSION.toString(), TxtKeys.DEVICE_ID to local.deviceId)

        /**
         * Returns the desktop, or null if the announcement must be ignored: another protocol
         * version, a missing or invalid id, an invalid port, or an address that is not a
         * private-network IPv4 address (an announcement must not send the phone to the internet).
         */
        fun fromAnnouncement(instanceName: String, host: String, port: Int, txt: Map<String, String?>): DiscoveredDesktop? {
            if (txt[TxtKeys.VERSION] != PROTOCOL_VERSION.toString()) return null
            val id = txt[TxtKeys.DEVICE_ID] ?: return null
            if (id.isBlank() || id.length > MAX_DEVICE_FIELD_LENGTH || id.any { it.isISOControl() }) return null
            if (!isPrivateIpv4(host)) return null
            val address = ServerAddress.parse("$host:$port") ?: return null
            return DiscoveredDesktop(id, cleanName(instanceName), address)
        }

        /** Display name from an untrusted instance name: no control or invisible characters, short. */
        private fun cleanName(name: String): String {
            val clean = name.filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }
                .trim()
                .take(MAX_DEVICE_FIELD_LENGTH)
                .trim()
            return clean.ifEmpty { UNNAMED }
        }

        /** 10/8, 172.16/12, 192.168/16 (private) and 169.254/16 (link-local). */
        fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { part ->
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return false
                part.toInt().takeIf { it <= 255 } ?: return false
            }
            val (a, b) = octets
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
    }
}

/**
 * The desktops currently visible, as announcements come and go. One desktop may be announced
 * several times (one per network interface, or again after an IP change); it is listed once,
 * with its most recent announcement.
 */
class DiscoveredDesktops {
    /** Instance name (unique per announcement) → desktop, in arrival order. */
    private val byInstance = LinkedHashMap<String, DiscoveredDesktop>()

    fun found(instanceName: String, desktop: DiscoveredDesktop) {
        byInstance.remove(instanceName)
        byInstance[instanceName] = desktop
    }

    fun lost(instanceName: String) {
        byInstance.remove(instanceName)
    }

    fun clear() = byInstance.clear()

    /** One entry per device id (latest announcement wins), sorted by name. */
    fun list(): List<DiscoveredDesktop> =
        byInstance.values.associateBy { it.deviceId }.values.sortedBy { it.name.lowercase() }

    /** The desktop with [deviceId], wherever it is now (its address may have changed). */
    fun find(deviceId: String): DiscoveredDesktop? = byInstance.values.lastOrNull { it.deviceId == deviceId }
}
