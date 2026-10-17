package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.ConnectionClosedException
import com.agnish.linkdrop.protocol.FrameChannel
import com.agnish.linkdrop.protocol.WireFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.utf8Size
import java.io.IOException

/** A frame from the peer was larger than allowed; the connection has been closed. */
class FrameTooLargeException(val size: Long) : IOException("frame of $size bytes exceeds the limit")

/**
 * [FrameChannel] on OkHttp's WebSocket, used directly (not through Ktor) because Ktor's OkHttp
 * engine ignores OkHttp's send buffer: once more than 16 MiB is unsent, OkHttp closes the
 * connection and later frames are silently dropped. Here, sending waits while the buffer is full.
 */
class OkHttpFrameChannel private constructor(private val maxFrameBytes: Int) : FrameChannel {
    private val frames = Channel<WireFrame>(capacity = INCOMING_BUFFER_FRAMES)
    override val incoming: ReceiveChannel<WireFrame> get() = frames

    private lateinit var webSocket: WebSocket
    private val opened = CompletableDeferred<Unit>()

    @Volatile
    private var closed = false

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            deliver(webSocket, WireFrame.Text(text), text.utf8Size())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            deliver(webSocket, WireFrame.Binary(bytes.toByteArray()), bytes.size.toLong())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            // Keep the desktop's reason (e.g. "1011 Ping timeout") so the user can see why.
            val normal = code == CLOSE_NORMAL || code == CLOSE_GOING_AWAY
            finish(if (normal) null else ConnectionClosedException("closed by the desktop: $code $reason".trim()))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.completeExceptionally(t)
            finish(t)
        }
    }

    // Called on OkHttp's reader thread. Note: OkHttp has already read the whole frame into memory;
    // the size check stops us from processing it, not from receiving it.
    private fun deliver(webSocket: WebSocket, frame: WireFrame, size: Long) {
        if (size > maxFrameBytes) {
            webSocket.close(CLOSE_TOO_BIG, "frame too large")
            finish(FrameTooLargeException(size))
            return
        }
        // Blocks the reader thread while our consumer is behind, so TCP slows the peer down
        // instead of frames piling up in memory.
        runCatching { runBlocking { frames.send(frame) } }
    }

    private fun finish(cause: Throwable?) {
        closed = true
        frames.close(cause)
    }

    override suspend fun sendText(text: String) = send { webSocket.send(text) }

    override suspend fun sendBinary(bytes: ByteArray) = send { webSocket.send(bytes.toByteString()) }

    private suspend fun send(enqueue: () -> Boolean) {
        while (webSocket.queueSize() > SEND_HIGH_WATER_BYTES) {
            if (closed) throw IOException("connection closed")
            delay(SEND_POLL_MILLIS)
        }
        if (!enqueue()) throw IOException("connection closed")
    }

    override fun close(code: Int, reason: String) {
        webSocket.close(code, reason.ifEmpty { null })
        // Unblock the reader thread if it is waiting for us to take a frame.
        frames.cancel()
        closed = true
    }

    companion object {
        /** Unsent bytes allowed in OkHttp's buffer before [send] waits. OkHttp fails at 16 MiB. */
        const val SEND_HIGH_WATER_BYTES = 1L * 1024 * 1024
        private const val SEND_POLL_MILLIS = 5L
        private const val INCOMING_BUFFER_FRAMES = 16
        private const val CLOSE_TOO_BIG = 1009
        private const val CLOSE_NORMAL = 1000
        private const val CLOSE_GOING_AWAY = 1001

        /** Opens a WebSocket to [url] (`ws://` or `wss://`). Throws if the connection fails. */
        suspend fun connect(client: OkHttpClient, url: String, maxFrameBytes: Int): OkHttpFrameChannel {
            val channel = OkHttpFrameChannel(maxFrameBytes)
            channel.webSocket = client.newWebSocket(Request.Builder().url(url).build(), channel.listener)
            try {
                channel.opened.await()
            } catch (e: CancellationException) {
                channel.webSocket.cancel()
                throw e
            }
            return channel
        }
    }
}
