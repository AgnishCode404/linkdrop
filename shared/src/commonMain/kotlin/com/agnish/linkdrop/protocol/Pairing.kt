package com.agnish.linkdrop.protocol

import com.agnish.linkdrop.transfer.newSha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pairing (PROTOCOL.md, "Security and pairing"). Each device has a self-signed certificate; a
 * device is identified by the SHA-256 fingerprint of that certificate. Two devices pair once,
 * after both users compared the same 6-digit code, and from then on recognise each other by
 * fingerprint.
 */
object Pairing {
    /** TLS keying material exporter label and context (RFC 5705 / RFC 8446 section 7.5). */
    const val EXPORTER_LABEL = "EXPORTER-linkdrop-pairing"
    val EXPORTER_CONTEXT: ByteArray = "LinkDrop pairing v1".encodeToByteArray()
    const val EXPORTER_LENGTH = 8

    /** How long the users have to confirm a pairing on either device. */
    const val CONFIRM_TIMEOUT_SECONDS = 120

    /**
     * The 6-digit code both screens show, from the [EXPORTER_LENGTH] bytes exported from the TLS
     * session. Both ends of one session export the same bytes; a man-in-the-middle has two
     * sessions and so (except with a one-in-a-million chance) two different codes.
     */
    fun code(exported: ByteArray): String {
        require(exported.size == EXPORTER_LENGTH) { "expected $EXPORTER_LENGTH exported bytes" }
        var value = 0uL
        for (byte in exported) value = (value shl 8) or byte.toUByte().toULong()
        return (value % 1_000_000uL).toString().padStart(6, '0')
    }

    /** `482913` → `482 913`, easier to compare at a glance. */
    fun formatCode(code: String): String = code.take(3) + " " + code.drop(3)
}

/** Certificate fingerprints: lowercase hex SHA-256 of the DER-encoded certificate (64 characters). */
object Fingerprints {
    fun of(certificateDer: ByteArray): String = newSha256().apply { update(certificateDer) }.hexDigest()

    fun isValid(fingerprint: String): Boolean =
        fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' }

    /** First 16 hex digits in groups of 4, e.g. `3f2a 9c1e 0b77 d4e2`, for showing to users. */
    fun short(fingerprint: String): String = fingerprint.take(16).chunked(4).joinToString(" ")
}

/** A device this device has paired with. */
@Serializable
data class PairedDevice(
    val deviceId: String,
    val name: String,
    /** [Fingerprints] format. */
    val certSha256: String,
    val pairedAtEpochSeconds: Long,
)

/**
 * The devices this device trusts. A device is trusted only if both its certificate fingerprint
 * and its device id match a pairing: a new certificate (e.g. after reinstalling) means pairing
 * again.
 */
class PairedDevices(devices: List<PairedDevice> = emptyList()) {
    private val byFingerprint = LinkedHashMap<String, PairedDevice>()

    init {
        devices.forEach { add(it) }
    }

    val all: List<PairedDevice> get() = byFingerprint.values.toList()

    /** The pairing for this certificate, if [deviceId] also matches. */
    fun trusted(deviceId: String, certSha256: String): PairedDevice? =
        byFingerprint[certSha256]?.takeIf { it.deviceId == deviceId }

    /** The pairing for a device id, whatever its certificate (to pin the desktop the phone expects). */
    fun byDeviceId(deviceId: String): PairedDevice? = byFingerprint.values.firstOrNull { it.deviceId == deviceId }

    /** Records a pairing. An older pairing with the same device id (an older certificate) is replaced. */
    fun add(device: PairedDevice) {
        require(Fingerprints.isValid(device.certSha256)) { "invalid fingerprint" }
        byFingerprint.values.removeAll { it.deviceId == device.deviceId }
        byFingerprint[device.certSha256] = device
    }

    /** Keeps the shown name current (names can change; the identity is the certificate). */
    fun rename(certSha256: String, name: String) {
        byFingerprint[certSha256]?.let { byFingerprint[certSha256] = it.copy(name = name) }
    }

    fun forget(certSha256: String) {
        byFingerprint.remove(certSha256)
    }

    fun encode(): String = json.encodeToString(Stored.serializer(), Stored(all))

    @Serializable
    private class Stored(val devices: List<PairedDevice>)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads what [encode] wrote. A damaged file gives an empty list (the user pairs again)
         * rather than a crash; entries with an invalid fingerprint are dropped.
         */
        fun decode(text: String): PairedDevices {
            val stored = runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull()
                ?: return PairedDevices()
            return PairedDevices(stored.devices.filter { Fingerprints.isValid(it.certSha256) })
        }
    }
}
