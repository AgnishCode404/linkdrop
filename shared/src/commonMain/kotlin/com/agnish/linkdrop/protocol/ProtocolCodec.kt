package com.agnish.linkdrop.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/** The outer JSON object of every control message: `{"v":1,"type":"…","id":"…","body":{…}}`. */
@Serializable
private data class Envelope(
    val v: Int,
    val type: String,
    val id: String,
    val body: JsonObject = JsonObject(emptyMap()),
)

/** Result of decoding one text frame. Only [Ok] carries a message; the rest say why not. */
sealed interface Decoded {
    data class Ok(val id: String, val message: Message) : Decoded

    /** Valid envelope, but a `type` this version does not know. The session should continue. */
    data class UnsupportedType(val id: String, val type: String) : Decoded

    /** Envelope `v` is not [PROTOCOL_VERSION]. The session should end. */
    data class UnsupportedVersion(val id: String, val version: Int) : Decoded

    /** Not valid JSON, or required fields missing or of the wrong type. */
    data class Malformed(val reason: String) : Decoded
}

/** Converts [Message]s to and from the JSON text sent in WebSocket text frames. */
object ProtocolCodec {
    private val json = Json {
        // Forward compatibility: newer peers may add fields we don't know yet.
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun newMessageId(): String = Uuid.random().toString()

    fun encode(message: Message, id: String = newMessageId()): String {
        // Exhaustive on purpose: a new Message type does not compile until it is listed here.
        val (type, body) = when (message) {
            is Hello -> TYPE_HELLO to json.encodeToJsonElement(Hello.serializer(), message)
            is HelloAck -> TYPE_HELLO_ACK to json.encodeToJsonElement(HelloAck.serializer(), message)
            is ErrorMessage -> TYPE_ERROR to json.encodeToJsonElement(ErrorMessage.serializer(), message)
            is TransferOffer -> TYPE_TRANSFER_OFFER to json.encodeToJsonElement(TransferOffer.serializer(), message)
            is TransferResponse ->
                TYPE_TRANSFER_RESPONSE to json.encodeToJsonElement(TransferResponse.serializer(), message)
            is FileBegin -> TYPE_FILE_BEGIN to json.encodeToJsonElement(FileBegin.serializer(), message)
            is FileEnd -> TYPE_FILE_END to json.encodeToJsonElement(FileEnd.serializer(), message)
            is FileResult -> TYPE_FILE_RESULT to json.encodeToJsonElement(FileResult.serializer(), message)
            is Cancel -> TYPE_CANCEL to json.encodeToJsonElement(Cancel.serializer(), message)
            is TransferDone -> TYPE_TRANSFER_DONE to json.encodeToJsonElement(TransferDone.serializer(), message)
        }
        return json.encodeToString(Envelope.serializer(), Envelope(PROTOCOL_VERSION, type, id, body.jsonObject))
    }

    fun decode(text: String): Decoded {
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (e: SerializationException) {
            return Decoded.Malformed("invalid envelope: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return Decoded.Malformed("invalid envelope: ${e.message}")
        }

        if (envelope.v != PROTOCOL_VERSION) {
            return Decoded.UnsupportedVersion(envelope.id, envelope.v)
        }

        val serializer: KSerializer<out Message> = when (envelope.type) {
            TYPE_HELLO -> Hello.serializer()
            TYPE_HELLO_ACK -> HelloAck.serializer()
            TYPE_ERROR -> ErrorMessage.serializer()
            TYPE_TRANSFER_OFFER -> TransferOffer.serializer()
            TYPE_TRANSFER_RESPONSE -> TransferResponse.serializer()
            TYPE_FILE_BEGIN -> FileBegin.serializer()
            TYPE_FILE_END -> FileEnd.serializer()
            TYPE_FILE_RESULT -> FileResult.serializer()
            TYPE_CANCEL -> Cancel.serializer()
            TYPE_TRANSFER_DONE -> TransferDone.serializer()
            else -> return Decoded.UnsupportedType(envelope.id, envelope.type)
        }

        return try {
            Decoded.Ok(envelope.id, json.decodeFromJsonElement(serializer, envelope.body))
        } catch (e: SerializationException) {
            Decoded.Malformed("invalid ${envelope.type} body: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Decoded.Malformed("invalid ${envelope.type} body: ${e.message}")
        }
    }

    private const val TYPE_HELLO = "hello"
    private const val TYPE_HELLO_ACK = "helloAck"
    private const val TYPE_ERROR = "error"
    private const val TYPE_TRANSFER_OFFER = "transferOffer"
    private const val TYPE_TRANSFER_RESPONSE = "transferResponse"
    private const val TYPE_FILE_BEGIN = "fileBegin"
    private const val TYPE_FILE_END = "fileEnd"
    private const val TYPE_FILE_RESULT = "fileResult"
    private const val TYPE_CANCEL = "cancel"
    private const val TYPE_TRANSFER_DONE = "transferDone"
}
