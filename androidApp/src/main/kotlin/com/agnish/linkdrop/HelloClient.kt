package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Handshake
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

/** Slice A client: connects to the desktop, sends hello, reports the reply, disconnects. */
object HelloClient {

    /** One OkHttpClient for the whole app, as OkHttp recommends (it shares threads and connections). */
    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // a WebSocket may be quiet for long periods
            .pingInterval(15, TimeUnit.SECONDS) // detects dead connections instead
            .build()
    }

    suspend fun sayHello(address: ServerAddress, local: LocalDevice, transport: TransportConfig): HelloResult {
        // Never fall back: a TLS config must not turn into a plaintext connection.
        if (transport is TransportConfig.Tls) return HelloResult.Failed("TLS is not implemented yet")

        var channel: OkHttpFrameChannel? = null
        return try {
            withTimeout(HELLO_TIMEOUT) {
                // ws:// is additionally blocked outside debug builds by the network security config.
                val url = "${transport.scheme}://${address.host}:${address.port}$SESSION_PATH"
                val connected = OkHttpFrameChannel.connect(okHttp, url, MAX_FRAME_BYTES).also { channel = it }
                connected.sendText(ProtocolCodec.encode(local.hello()))
                val reply = connected.incoming.receiveCatching()
                reply.exceptionOrNull()?.let { throw it }
                when (val frame = reply.getOrNull()) {
                    is WireFrame.Text -> Handshake.interpretReply(ProtocolCodec.decode(frame.text))
                    is WireFrame.Binary -> HelloResult.Failed("unexpected reply from server")
                    null -> HelloResult.Failed("server closed the connection")
                }
            }
        } catch (e: TimeoutCancellationException) {
            HelloResult.Failed("no answer within ${HELLO_TIMEOUT.inWholeSeconds} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HelloResult.Failed(describe(e))
        } finally {
            channel?.close(1000, "done")
        }
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
