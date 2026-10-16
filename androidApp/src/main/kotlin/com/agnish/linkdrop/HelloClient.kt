package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Decoded
import com.agnish.linkdrop.protocol.Handshake
import com.agnish.linkdrop.protocol.HelloAck
import com.agnish.linkdrop.protocol.HelloResult
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.MAX_FRAME_BYTES
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.ServerAddress
import com.agnish.linkdrop.protocol.TransportConfig
import com.agnish.linkdrop.protocol.WireFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private val HELLO_TIMEOUT = 10.seconds

/** Result of [HelloClient.connect]. */
sealed interface ConnectResult {
    /** Handshake done; [channel] stays open for the transfer session. */
    class Opened(val channel: OkHttpFrameChannel, val ack: HelloAck) : ConnectResult

    data class Failed(val reason: String) : ConnectResult
}

/** Connects to the desktop and does the hello handshake. */
object HelloClient {

    /** One OkHttpClient for the whole app, as OkHttp recommends (it shares threads and connections). */
    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // a WebSocket may be quiet for long periods
            .pingInterval(15, TimeUnit.SECONDS) // detects dead connections instead
            .build()
    }

    suspend fun connect(
        address: ServerAddress,
        local: LocalDevice,
        transport: TransportConfig,
        client: OkHttpClient = okHttp,
    ): ConnectResult {
        // Never fall back: a TLS config must not turn into a plaintext connection.
        if (transport is TransportConfig.Tls) return ConnectResult.Failed("TLS is not implemented yet")

        var channel: OkHttpFrameChannel? = null
        var result: ConnectResult = ConnectResult.Failed("not connected")
        try {
            result = withTimeout(HELLO_TIMEOUT) {
                // ws:// is additionally blocked outside debug builds by the network security config.
                val url = "${transport.scheme}://${address.host}:${address.port}$SESSION_PATH"
                val opened = OkHttpFrameChannel.connect(client, url, MAX_FRAME_BYTES).also { channel = it }
                opened.sendText(ProtocolCodec.encode(local.hello()))
                val reply = opened.incoming.receiveCatching()
                reply.exceptionOrNull()?.let { throw it }
                when (val frame = reply.getOrNull()) {
                    is WireFrame.Text -> {
                        val decoded = ProtocolCodec.decode(frame.text)
                        when (val outcome = Handshake.interpretReply(decoded)) {
                            is HelloResult.Connected ->
                                ConnectResult.Opened(opened, (decoded as Decoded.Ok).message as HelloAck)
                            is HelloResult.Failed -> ConnectResult.Failed(outcome.reason)
                        }
                    }
                    is WireFrame.Binary -> ConnectResult.Failed("unexpected reply from server")
                    null -> ConnectResult.Failed("server closed the connection")
                }
            }
        } catch (e: TimeoutCancellationException) {
            result = ConnectResult.Failed("no answer within ${HELLO_TIMEOUT.inWholeSeconds} s")
        } catch (e: CancellationException) {
            channel?.close(1000, "cancelled")
            throw e
        } catch (e: Exception) {
            result = ConnectResult.Failed(describe(e))
        } finally {
            if (result !is ConnectResult.Opened) channel?.close(1000, "done")
        }
        return result
    }

    private fun describe(e: Exception): String = when (e) {
        is FrameTooLargeException -> "reply from server too large (${e.size} bytes)"
        is UnknownServiceException -> "plaintext connection blocked (only debug builds allow ws://)"
        is ConnectException -> "could not connect: is the desktop app running, on the same Wi-Fi?"
        is NoRouteToHostException -> "no route to that address: are both devices on the same Wi-Fi?"
        is SocketTimeoutException -> "connection timed out: check the address and the Wi-Fi"
        is UnknownHostException -> "unknown host name"
        is SecurityException -> "local network access was denied in Android settings"
        else -> "${e::class.simpleName}: ${e.message}"
    }
}
