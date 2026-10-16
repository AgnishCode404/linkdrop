package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.FrameChannel
import com.agnish.linkdrop.protocol.WireFrame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * [FrameChannel] on a Ktor WebSocket (server, or client in tests). Ktor's `send` already waits
 * while the socket is busy, so no extra flow control is needed here (unlike OkHttp on Android).
 */
class KtorFrameChannel(private val session: DefaultWebSocketSession) : FrameChannel {
    private val frames = Channel<WireFrame>(capacity = 16)
    override val incoming: ReceiveChannel<WireFrame> get() = frames

    init {
        session.launch {
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> frames.send(WireFrame.Text(frame.readText()))
                        is Frame.Binary -> frames.send(WireFrame.Binary(frame.readBytes()))
                        else -> Unit
                    }
                }
                frames.close()
            } catch (e: CancellationException) {
                frames.close()
                throw e
            } catch (e: Throwable) {
                frames.close(e)
            }
        }
    }

    override suspend fun sendText(text: String) = session.send(Frame.Text(text))

    override suspend fun sendBinary(bytes: ByteArray) = session.send(Frame.Binary(true, bytes))

    override fun close(code: Int, reason: String) {
        session.launch { session.close(CloseReason(code.toShort(), reason)) }
    }
}
