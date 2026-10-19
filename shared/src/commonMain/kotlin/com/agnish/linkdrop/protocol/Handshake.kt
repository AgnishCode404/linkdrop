package com.agnish.linkdrop.protocol

/** Limit on peer-supplied identity strings; they are shown in the UI and must stay short. */
const val MAX_DEVICE_FIELD_LENGTH = 64

/** This device's side of the handshake, sent as [Hello] or [HelloAck]. */
data class LocalDevice(
    val deviceId: String,
    val deviceName: String,
    val features: List<String> = emptyList(),
    val maxChunkBytes: Int = DEFAULT_MAX_CHUNK_BYTES,
) {
    fun hello() = Hello(deviceId, deviceName, PROTOCOL_VERSION, features, maxChunkBytes)
    fun helloAck() = HelloAck(deviceId, deviceName, PROTOCOL_VERSION, features, maxChunkBytes)
}

/** Outcome of the client's hello, as shown to the user. */
sealed interface HelloResult {
    data class Connected(val serverName: String) : HelloResult
    data class Failed(val reason: String) : HelloResult
}

object Handshake {
    /**
     * Server side: the reply to a peer's [Hello]. Returns [HelloAck] if the peer is compatible,
     * otherwise an [ErrorMessage] after which the connection should be closed.
     */
    fun respondTo(hello: Hello, helloId: String, local: LocalDevice): Message {
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            return ErrorMessage(
                code = ErrorCodes.VERSION_MISMATCH,
                message = "expected protocol $PROTOCOL_VERSION, got ${hello.protocolVersion}",
                ref = helloId,
            )
        }
        val problem = validateIdentity(hello.deviceId, hello.deviceName)
        if (problem != null) {
            return ErrorMessage(code = ErrorCodes.BAD_REQUEST, message = problem, ref = helloId)
        }
        return local.helloAck()
    }

    /** Client side: turns the server's first reply into a result to show the user. */
    fun interpretReply(reply: Decoded): HelloResult = when (reply) {
        is Decoded.Ok -> when (val message = reply.message) {
            is HelloAck -> checkAck(message)?.let { HelloResult.Failed(it) }
                ?: HelloResult.Connected(message.deviceName)
            is ErrorMessage -> HelloResult.Failed(
                "server refused: ${message.code}" + (message.message?.let { " ($it)" } ?: ""),
            )
            else -> HelloResult.Failed("unexpected reply from server")
        }
        is Decoded.UnsupportedType -> HelloResult.Failed("unexpected reply from server: ${reply.type}")
        is Decoded.UnsupportedVersion -> HelloResult.Failed("server uses envelope version ${reply.version}")
        is Decoded.Malformed -> HelloResult.Failed("unreadable reply from server")
    }

    /** Client side: checks the server's [HelloAck]. Returns a problem description, or null if OK. */
    fun checkAck(ack: HelloAck): String? {
        if (ack.protocolVersion != PROTOCOL_VERSION) {
            return "server speaks protocol ${ack.protocolVersion}, expected $PROTOCOL_VERSION"
        }
        return validateIdentity(ack.deviceId, ack.deviceName)
    }

    private fun validateIdentity(deviceId: String, deviceName: String): String? = when {
        deviceId.isBlank() || deviceId.length > MAX_DEVICE_FIELD_LENGTH -> "invalid deviceId"
        deviceName.isBlank() || deviceName.length > MAX_DEVICE_FIELD_LENGTH -> "invalid deviceName"
        // Control characters, and invisible formatting ones (e.g. right-to-left override) that could
        // disguise the name shown in the desktop's "wants to connect" dialog.
        deviceName.any { it.isISOControl() || it.category == CharCategory.FORMAT } -> "invalid deviceName"
        else -> null
    }
}
