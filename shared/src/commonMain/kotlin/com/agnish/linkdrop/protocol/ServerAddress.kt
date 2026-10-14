package com.agnish.linkdrop.protocol

/** Where the desktop server listens, as typed by the user: `192.168.1.20` or `192.168.1.20:47800`. */
data class ServerAddress(val host: String, val port: Int) {
    companion object {
        /** Returns null if [input] is not an IPv4 address or host name with an optional port. */
        fun parse(input: String): ServerAddress? {
            val parts = input.trim().split(':')
            if (parts.size > 2) return null
            val host = parts[0]
            if (!isIpv4(host) && !isHostName(host)) return null
            val port = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else DEFAULT_PORT
            if (port !in 1..65535) return null
            return ServerAddress(host, port)
        }

        private fun isIpv4(text: String): Boolean {
            val octets = text.split('.')
            return octets.size == 4 && octets.all { octet ->
                octet.length in 1..3 && octet.all { it in '0'..'9' } && octet.toInt() <= 255
            }
        }

        private fun isHostName(text: String): Boolean {
            if (text.isEmpty() || text.length > 253) return false
            // Four all-digit labels would be an invalid IPv4 address (e.g. 1.2.3.999), not a name.
            if (text.split('.').all { label -> label.isNotEmpty() && label.all { it in '0'..'9' } }) return false
            return text.split('.').all { label ->
                label.length in 1..63 &&
                    label.first() != '-' && label.last() != '-' &&
                    label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
            }
        }
    }
}
