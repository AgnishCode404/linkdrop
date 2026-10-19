package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.ErrorCodes
import com.agnish.linkdrop.protocol.ErrorMessage
import com.agnish.linkdrop.protocol.Handshake
import com.agnish.linkdrop.protocol.HelloAck
import com.agnish.linkdrop.protocol.HelloResult
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.MAX_FRAME_BYTES
import com.agnish.linkdrop.protocol.PairedDevice
import com.agnish.linkdrop.protocol.Pairing
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.ServerAddress
import com.agnish.linkdrop.protocol.TransportConfig
import com.agnish.linkdrop.protocol.WireFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds

/** For a paired desktop (or plaintext): connecting, and then the desktop's answer to hello. */
private val CONNECT_TIMEOUT = 10.seconds
private val HELLO_TIMEOUT = 10.seconds

/** While pairing, the desktop's user still has to click Allow (plus some slack for the network). */
private val PAIRING_REPLY_TIMEOUT = (Pairing.CONFIRM_TIMEOUT_SECONDS + 15).seconds

/** Result of [HelloClient.connect]. */
sealed interface ConnectResult {
    /**
     * Handshake done; [channel] stays open for the transfer session. [desktopCertSha256] is null
     * over plaintext. [pairedNow]: this connection was a new pairing, to be remembered.
     */
    class Opened(
        val channel: OkHttpFrameChannel,
        val ack: HelloAck,
        val desktopCertSha256: String?,
        val pairedNow: Boolean,
    ) : ConnectResult

    data class Failed(val reason: String) : ConnectResult
}

/** Pairing with a new desktop: the phone's half of the code comparison. */
interface PairingUser {
    /** Shows [code]; returns true if the user says the desktop shows the same code. */
    suspend fun confirm(code: String): Boolean

    /** The user confirmed; now the desktop's user has to click Allow. */
    fun waitingForDesktop(code: String) = Unit
}

/** Connects to the desktop and does the handshake (with pairing, if the desktop is new). */
object HelloClient {

    /** One OkHttpClient for the whole app, as OkHttp recommends (it shares threads and connections). */
    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // a WebSocket may be quiet for long periods
            // Detects dead connections instead. OkHttp gives up if a pong has not arrived by the
            // next ping. While a large file is arriving, the pong waits behind file data until the
            // phone's storage has written it, so 15 s was too tight; 30 s matches the desktop's
            // own ping timeout.
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Connects to [address]. Over TLS the desktop is identified by its certificate: pinned by
     * [TransportConfig.Tls.pinnedCertSha256], or found in [paired], or else this is a pairing and
     * [user] compares codes before hello is sent.
     */
    suspend fun connect(
        address: ServerAddress,
        local: LocalDevice,
        transport: TransportConfig,
        tls: PhoneTls? = null,
        paired: PairedDesktops? = null,
        user: PairingUser? = null,
        client: OkHttpClient = okHttp,
    ): ConnectResult {
        val trust = (transport as? TransportConfig.Tls)?.let { DesktopTrust(it.pinnedCertSha256) }
        var channel: OkHttpFrameChannel? = null
        var result: ConnectResult = ConnectResult.Failed("not connected")
        try {
            val http = if (trust == null) {
                client
            } else {
                val phoneTls = tls ?: return ConnectResult.Failed("this build cannot make secure connections")
                tlsClient(client, withContext(Dispatchers.IO) { phoneTls.identity() }, trust)
            }
            // ws:// is additionally blocked outside debug builds by the network security config.
            val url = "${transport.scheme}://${address.host}:${address.port}$SESSION_PATH"
            val opened = withTimeout(CONNECT_TIMEOUT) { OkHttpFrameChannel.connect(http, url, MAX_FRAME_BYTES) }
            channel = opened

            // Over TLS: which desktop is this? A paired one, or a pairing the users must confirm.
            var known: PairedDevice? = null
            var pairing = false
            if (trust != null) {
                val certSha256 = checkNotNull(trust.seenCertSha256)
                known = paired?.byFingerprint(certSha256)
                if (known == null) {
                    val socket = trust.socket ?: return ConnectResult.Failed("no pairing code (TLS socket unavailable)")
                    val code = Pairing.code(tls!!.exporter.export(socket))
                    val confirmed = user != null &&
                        withTimeoutOrNull(Pairing.CONFIRM_TIMEOUT_SECONDS.seconds) { user.confirm(code) } == true
                    if (!confirmed) {
                        result = ConnectResult.Failed("pairing cancelled")
                        return result
                    }
                    pairing = true
                    user.waitingForDesktop(code)
                }
            }

            opened.sendText(ProtocolCodec.encode(local.hello()))
            result = withTimeout(if (pairing) PAIRING_REPLY_TIMEOUT else HELLO_TIMEOUT) {
                val reply = opened.incoming.receiveCatching()
                reply.exceptionOrNull()?.let { throw it }
                when (val frame = reply.getOrNull()) {
                    is WireFrame.Text -> interpret(ProtocolCodec.decode(frame.text), opened, trust?.seenCertSha256, known, pairing)
                    is WireFrame.Binary -> ConnectResult.Failed("unexpected reply from server")
                    null -> ConnectResult.Failed("server closed the connection")
                }
            }
        } catch (e: TimeoutCancellationException) {
            result = ConnectResult.Failed(if (channel == null) "could not connect in time" else "no answer from the desktop in time")
        } catch (e: CancellationException) {
            channel?.close(1000, "cancelled")
            throw e
        } catch (e: Exception) {
            result = ConnectResult.Failed(describe(e, transport, trust))
        } finally {
            if (result !is ConnectResult.Opened) channel?.close(1000, "done")
        }
        return result
    }

    private fun interpret(
        reply: Decoded,
        channel: OkHttpFrameChannel,
        certSha256: String?,
        known: PairedDevice?,
        pairing: Boolean,
    ): ConnectResult {
        val error = (reply as? Decoded.Ok)?.message as? ErrorMessage
        when (error?.code) {
            ErrorCodes.NOT_ALLOWED -> return ConnectResult.Failed("the desktop did not allow the connection")
            ErrorCodes.BUSY -> return ConnectResult.Failed("the desktop is busy with another phone; try again in a moment")
        }
        return when (val outcome = Handshake.interpretReply(reply)) {
            is HelloResult.Failed -> ConnectResult.Failed(outcome.reason)
            is HelloResult.Connected -> {
                val ack = (reply as Decoded.Ok).message as HelloAck
                if (known != null && ack.deviceId != known.deviceId) {
                    ConnectResult.Failed("the desktop's device id does not match its pairing")
                } else {
                    ConnectResult.Opened(channel, ack, certSha256, pairedNow = pairing)
                }
            }
        }
    }

    /** OkHttp for one TLS connection: TLS 1.3 only, the phone's certificate, the desktop check. */
    private fun tlsClient(base: OkHttpClient, identity: ClientIdentity, trust: DesktopTrust): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf(IdentityKeyManager(identity)), arrayOf(trust), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            // Desktops are reached by IP address with self-signed certificates: identity is checked
            // by certificate fingerprint (DesktopTrust), not by host name.
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
            .build()
    }

    private fun describe(e: Exception, transport: TransportConfig, trust: DesktopTrust?): String {
        val pinned = (transport as? TransportConfig.Tls)?.pinnedCertSha256
        if (pinned != null && trust?.seenCertSha256 != null && trust.seenCertSha256 != pinned) {
            return "this desktop's identity has changed. If LinkDrop was reinstalled or reset on the desktop, " +
                "forget it under Paired desktops and pair again. Otherwise another device may be pretending to be it."
        }
        return when (e) {
            is FrameTooLargeException -> "reply from server too large (${e.size} bytes)"
            is UnknownServiceException -> "plaintext connection blocked (only debug builds allow ws://)"
            is ConnectException -> "could not connect: is the desktop app running, on the same Wi-Fi?"
            is NoRouteToHostException -> "no route to that address: are both devices on the same Wi-Fi?"
            is SocketTimeoutException -> "connection timed out: check the address and the Wi-Fi"
            is UnknownHostException -> "unknown host name"
            is SecurityException -> "local network access was denied in Android settings"
            is SSLException -> "secure connection failed (${e.message}). Is the desktop app up to date?"
            else -> "${e::class.simpleName}: ${e.message}"
        }
    }
}
