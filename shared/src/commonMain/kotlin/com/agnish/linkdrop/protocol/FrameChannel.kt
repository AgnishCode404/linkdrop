package com.agnish.linkdrop.protocol

import kotlinx.coroutines.channels.ReceiveChannel

/** One WebSocket frame, independent of the WebSocket library used by each app. */
sealed interface WireFrame {
    class Text(val text: String) : WireFrame
    class Binary(val bytes: ByteArray) : WireFrame
}

/** One WebSocket connection, as the protocol and transfer code see it. */
interface FrameChannel {
    /**
     * Frames from the peer, in order. Closed when the connection ends; if it ended because of
     * an error, receiving rethrows that error.
     */
    val incoming: ReceiveChannel<WireFrame>

    /** Sends a frame. Suspends while the connection cannot take more data (flow control). */
    suspend fun sendText(text: String)

    /** Sends a frame. Suspends while the connection cannot take more data (flow control). */
    suspend fun sendBinary(bytes: ByteArray)

    /** Starts a graceful close. Frames not yet received are discarded. */
    fun close(code: Int = 1000, reason: String = "")
}
