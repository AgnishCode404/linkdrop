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

// ---- File transfer (Slice B). Used only when both sides list FEATURE_TRANSFER in the handshake. ----

/** Feature name advertised in [Hello.features] / [HelloAck.features] by peers that can transfer files. */
const val FEATURE_TRANSFER = "transfer"

/** One file in a [TransferOffer]. [name] is untrusted: see [FileNames.clean]. */
@Serializable
data class OfferedFile(
    val fileId: Int,
    val name: String,
    val size: Long,
    val mimeType: String? = null,
)

@Serializable
data class TransferOffer(
    val transferId: String,
    val files: List<OfferedFile>,
) : Message

/** The receiver's answer for one offered file. */
@Serializable
data class FileDecision(
    val fileId: Int,
    val accept: Boolean,
    val resumeOffset: Long = 0,
)

@Serializable
data class TransferResponse(
    val transferId: String,
    val files: List<FileDecision>,
) : Message

@Serializable
data class FileBegin(
    val transferId: String,
    val fileId: Int,
    val offset: Long = 0,
) : Message

@Serializable
data class FileEnd(
    val transferId: String,
    val fileId: Int,
    /** Lowercase hex SHA-256 of the whole file. */
    val sha256: String,
) : Message

@Serializable
data class FileResult(
    val transferId: String,
    val fileId: Int,
    val ok: Boolean,
    /** One of [TransferErrors], when [ok] is false. */
    val error: String? = null,
) : Message

@Serializable
data class Cancel(
    val transferId: String,
    /** Null cancels the rest of the transfer. */
    val fileId: Int? = null,
) : Message

@Serializable
data class TransferDone(
    val transferId: String,
) : Message

/** Values for [FileResult.error]. */
object TransferErrors {
    const val REJECTED = "rejected"
    const val CANCELLED = "cancelled"
    const val CHECKSUM_MISMATCH = "checksum_mismatch"
    const val SIZE_MISMATCH = "size_mismatch"
    const val TOO_LARGE = "too_large"
    const val IO_ERROR = "io_error"
    const val UNKNOWN_TRANSFER = "unknown_transfer"
}

/** Receiver-side limits (PROTOCOL.md, "Receiver rules"). */
object TransferLimits {
    /** An offer with more files than this is answered with `error{bad_request}`. */
    const val MAX_FILES_PER_OFFER = 1000

    /** File names longer than this many UTF-8 bytes are shortened (see [FileNames.clean]). */
    const val MAX_NAME_BYTES = 255

    /** An offer the user has not answered after this many seconds is declined. */
    const val DECISION_TIMEOUT_SECONDS = 120
}
