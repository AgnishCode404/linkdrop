package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.WireFrame
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs [OkHttpFrameChannel] over real sockets against a local Ktor server that reads or writes
 * at roughly Wi-Fi speed, so OkHttp's send buffer fills up the way it does on a phone.
 */
class OkHttpFrameChannelTest {

    private val total = 50L * 1024 * 1024
    private val chunkSize = 64 * 1024

    /** Deterministic test data, so both ends can compute the same checksum. */
    private fun chunk(index: Int) = Random(index).nextBytes(chunkSize)

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun withServer(handler: suspend DefaultWebSocketServerSession.() -> Unit, block: suspend (url: String) -> Unit) {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing { webSocket("/test") { handler() } }
        }.start(wait = false)
        try {
            runBlocking {
                val port = server.engine.resolvedConnectors().first().port
                block("ws://127.0.0.1:$port/test")
            }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    @Test
    fun sends50MbToSlowReaderWithoutLosingData() = withServer(
        handler = {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        digest.update(frame.data)
                        count += frame.data.size
                        delay(3) // ~64 KiB per 3 ms ≈ 20 MB/s, roughly Wi-Fi speed
                    }
                    is Frame.Text -> {
                        send(Frame.Text("$count ${digest.digest().hex()}"))
                        break
                    }
                    else -> Unit
                }
            }
        },
    ) { url ->
        val channel = OkHttpFrameChannel.connect(HelloClient.okHttp, url, maxFrameBytes = 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        for (i in 0 until (total / chunkSize).toInt()) {
            val bytes = chunk(i)
            digest.update(bytes)
            channel.sendBinary(bytes)
        }
        channel.sendText("done")
        val reply = assertIs<WireFrame.Text>(channel.incoming.receive())
        assertEquals("$total ${digest.digest().hex()}", reply.text)
        channel.close()
    }

    @Test
    fun receives50MbWhileConsumingSlowlyWithoutLosingData() = withServer(
        handler = {
            val digest = MessageDigest.getInstance("SHA-256")
            for (i in 0 until (total / chunkSize).toInt()) {
                val bytes = chunk(i)
                digest.update(bytes)
                send(Frame.Binary(true, bytes))
            }
            send(Frame.Text("$total ${digest.digest().hex()}"))
            // Wait for the client to close.
            for (frame in incoming) Unit
        },
    ) { url ->
        val channel = OkHttpFrameChannel.connect(HelloClient.okHttp, url, maxFrameBytes = chunkSize)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        while (true) {
            when (val frame = channel.incoming.receive()) {
                is WireFrame.Binary -> {
                    digest.update(frame.bytes)
                    count += frame.bytes.size
                    delay(3)
                }
                is WireFrame.Text -> {
                    assertEquals(frame.text, "$count ${digest.digest().hex()}")
                    break
                }
            }
        }
        assertEquals(total, count)
        channel.close()
    }

    @Test
    fun sendingAfterPeerClosedFails() = withServer(
        handler = { close(CloseReason(CloseReason.Codes.NORMAL, "bye")) },
    ) { url ->
        val channel = OkHttpFrameChannel.connect(HelloClient.okHttp, url, maxFrameBytes = 1024)
        // The peer's close arrives asynchronously; once it has, sends must fail instead of vanishing.
        assertFailsWith<IOException> {
            repeat(10_000) {
                channel.sendBinary(ByteArray(1024))
                delay(1)
            }
        }
    }

    @Test
    fun oversizedFrameFromPeerEndsTheConnection() = withServer(
        handler = {
            send(Frame.Text("x".repeat(2048)))
            for (frame in incoming) Unit
        },
    ) { url ->
        val channel = OkHttpFrameChannel.connect(HelloClient.okHttp, url, maxFrameBytes = 1024)
        val error = assertFailsWith<FrameTooLargeException> { channel.incoming.receive() }
        assertEquals(2048L, error.size)
    }

    @Test
    fun abnormalCloseByTheDesktopKeepsItsReason() = withServer(
        handler = { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Ping timeout")) },
    ) { url ->
        val channel = OkHttpFrameChannel.connect(HelloClient.okHttp, url, maxFrameBytes = 1024)
        val error = assertFailsWith<com.agnish.linkdrop.protocol.ConnectionClosedException> { channel.incoming.receive() }
        assertEquals("closed by the desktop: 1011 Ping timeout", error.message)
    }

    @Test
    fun normalCloseIsNotAnError() = withServer(
        handler = { close(CloseReason(CloseReason.Codes.NORMAL, "bye")) },
    ) { url ->
        val channel = OkHttpFrameChannel.connect(HelloClient.okHttp, url, maxFrameBytes = 1024)
        val result = channel.incoming.receiveCatching()
        assertTrue(result.isClosed)
        assertEquals(null, result.exceptionOrNull())
    }

    @Test
    fun textFramesArriveInOrder() = withServer(
        handler = {
            for (frame in incoming) {
                if (frame is Frame.Text) send(Frame.Text("echo:" + frame.readText()))
            }
        },
    ) { url ->
        val channel = OkHttpFrameChannel.connect(HelloClient.okHttp, url, maxFrameBytes = 1024)
        repeat(100) { channel.sendText("m$it") }
        repeat(100) { assertEquals("echo:m$it", assertIs<WireFrame.Text>(channel.incoming.receive()).text) }
        channel.close()
    }
}
