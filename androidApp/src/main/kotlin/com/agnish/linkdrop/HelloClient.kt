package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Handshake
import com.agnish.linkdrop.protocol.HelloResult
import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.MAX_FRAME_BYTES
import com.agnish.linkdrop.protocol.ProtocolCodec
import com.agnish.linkdrop.protocol.SESSION_PATH
import com.agnish.linkdrop.protocol.ServerAddress
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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

    suspend fun sayHello(address: ServerAddress, local: LocalDevice): HelloResult {
        val client = HttpClient(OkHttp) {
            engine {
                config { connectTimeout(5, TimeUnit.SECONDS) }
            }
            // No maxFrameSize here: the OkHttp engine rejects that setting at connect time.
            // Received frames are size-checked below instead.
            install(WebSockets)
        }
        return try {
            withTimeout(HELLO_TIMEOUT) {
                var result: HelloResult = HelloResult.Failed("no reply from server")
                // Plaintext ws:// for Slice A; allowed only by the debug network security config.
                client.webSocket(host = address.host, port = address.port, path = SESSION_PATH) {
                    send(Frame.Text(ProtocolCodec.encode(local.hello())))
                    val frame = incoming.receiveCatching().getOrNull()
                    result = when {
                        frame != null && frame.data.size > MAX_FRAME_BYTES ->
                            HelloResult.Failed("reply from server too large (${frame.data.size} bytes)")
                        frame is Frame.Text -> Handshake.interpretReply(ProtocolCodec.decode(frame.readText()))
                        frame == null -> HelloResult.Failed("server closed the connection")
                        else -> HelloResult.Failed("unexpected reply from server")
                    }
                    close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                }
                result
            }
        } catch (e: TimeoutCancellationException) {
            HelloResult.Failed("no answer within ${HELLO_TIMEOUT.inWholeSeconds} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HelloResult.Failed(describe(e))
        } finally {
            client.close()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownServiceException -> "plaintext connection blocked (only debug builds allow ws://)"
        is ConnectException -> "could not connect: is the desktop app running, on the same Wi-Fi?"
        is NoRouteToHostException -> "no route to that address: are both devices on the same Wi-Fi?"
        is SocketTimeoutException -> "connection timed out: check the address and the Wi-Fi"
        is UnknownHostException -> "unknown host name"
        is SecurityException -> "local network access was denied in Android settings"
        else -> "${e::class.simpleName}: ${e.message}"
    }
}
