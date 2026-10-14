package com.agnish.linkdrop.protocol

import kotlinx.serialization.Serializable

/** Wire format version, sent as `v` in every envelope. See docs/PROTOCOL.md. */
const val PROTOCOL_VERSION = 1

/** WebSocket path the desktop server listens on. */
const val SESSION_PATH = "/v1/session"

const val DEFAULT_PORT = 47800

/** Largest binary chunk this implementation sends or accepts (used from Slice B). */
const val DEFAULT_MAX_CHUNK_BYTES = 64 * 1024

/** Largest WebSocket frame accepted: one binary chunk plus its 4-byte header (see PROTOCOL.md). */
const val MAX_FRAME_BYTES = DEFAULT_MAX_CHUNK_BYTES + 4

/**
 * A control message, carried as the `body` of a JSON envelope in a WebSocket text frame.
 *
 * Slice A implements only [Hello], [HelloAck] and [ErrorMessage]. The transfer messages are
 * specified in docs/PROTOCOL.md and are added in Slice B without changing the envelope.
 */
sealed interface Message

@Serializable
data class Hello(
    val deviceId: String,
    val deviceName: String,
    val protocolVersion: Int,
    val features: List<String> = emptyList(),
    val maxChunkBytes: Int = DEFAULT_MAX_CHUNK_BYTES,
) : Message

@Serializable
data class HelloAck(
    val deviceId: String,
    val deviceName: String,
    val protocolVersion: Int,
    val features: List<String> = emptyList(),
    val maxChunkBytes: Int = DEFAULT_MAX_CHUNK_BYTES,
) : Message

/** Named `ErrorMessage` so it does not clash with Kotlin's built-in `Error` class. */
@Serializable
data class ErrorMessage(
    val code: String,
    val message: String? = null,
    /** The `id` of the message this error is about, if any. */
    val ref: String? = null,
) : Message

/** Values for [ErrorMessage.code]. */
object ErrorCodes {
    const val UNSUPPORTED_TYPE = "unsupported_type"
    const val VERSION_MISMATCH = "version_mismatch"
    const val MALFORMED = "malformed"
    const val BAD_REQUEST = "bad_request"
}
