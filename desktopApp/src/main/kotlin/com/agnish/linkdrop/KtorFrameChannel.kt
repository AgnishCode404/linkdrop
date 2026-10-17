package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.ConnectionClosedException
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

/** [FrameChannel] on a Ktor WebSocket (the desktop server, or the client in tests). */
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
                frames.close(abnormalClose())
            } catch (e: CancellationException) {
                frames.close()
                throw e
            } catch (e: Throwable) {
                frames.close(e)
            }
        }
    }

    /** Why the session ended, if it was not a normal close (e.g. Ktor's own "Ping timeout"). */
    private suspend fun abnormalClose(): Throwable? {
        val reason = runCatching { session.closeReason.await() }.getOrNull() ?: return null
        if (reason.knownReason == CloseReason.Codes.NORMAL || reason.knownReason == CloseReason.Codes.GOING_AWAY) return null
        return ConnectionClosedException("closed: ${reason.code} ${reason.message}".trim())
    }

    override suspend fun sendText(text: String) = send(Frame.Text(text))

    override suspend fun sendBinary(bytes: ByteArray) = send(Frame.Binary(true, bytes))

    /**
     * Ktor's Netty engine does not wait for the network by itself: without flush() it accepts
     * frames as fast as they come and keeps everything unsent in memory (measured: a whole
     * 200 MB file within a second). flush() waits until earlier frames reached the socket.
     */
    private suspend fun send(frame: Frame) {
        session.send(frame)
        session.flush()
    }

    override fun close(code: Int, reason: String) {
        session.launch { session.close(CloseReason(code.toShort(), reason)) }
    }
}
