package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.LocalDevice
import com.agnish.linkdrop.protocol.PairedDevice
import com.agnish.linkdrop.protocol.ServerAddress
import com.agnish.linkdrop.protocol.TransportConfig
import com.agnish.linkdrop.transfer.FileSink
import com.agnish.linkdrop.transfer.IncomingOffer
import com.agnish.linkdrop.transfer.OfferDecider
import com.agnish.linkdrop.transfer.TransferProgress
import com.agnish.linkdrop.transfer.TransferSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.Instant

/** An offer shown on screen until the user answers (or it times out). */
class PendingOffer(val offer: IncomingOffer) {
    val answer = CompletableDeferred<Boolean>()
}

/**
 * The phone's connection to one desktop. It lives as long as the app process (see
 * [LinkDropApplication]), so rotating the screen or reopening the activity does not drop it.
 *
 * [allowPlaintext] is the development switch (BuildConfig.ALLOW_PLAINTEXT); without it every
 * connection is TLS, using [tls] and remembering desktops in [paired].
 */
class PhoneConnection(
    private val local: LocalDevice,
    private val sink: FileSink,
    private val scope: CoroutineScope,
    private val allowPlaintext: Boolean,
    private val tls: PhoneTls?,
    val paired: PairedDesktops?,
    private val okHttp: OkHttpClient = HelloClient.okHttp,
) {
    sealed interface State {
        /** [reason] is null when the user disconnected (or never connected). */
        data class Disconnected(val reason: String? = null) : State
        data class Connecting(val address: ServerAddress) : State

        /** A new desktop: the user compares [code] with the desktop's screen. */
        class ConfirmPairing(val address: ServerAddress, val code: String, internal val answer: CompletableDeferred<Boolean>) : State {
            fun confirm() {
                answer.complete(true)
            }

            fun cancel() {
                answer.complete(false)
            }
        }

        /** The user confirmed the code; the desktop's user still has to click Allow. */
        data class WaitingForDesktop(val address: ServerAddress, val code: String) : State

        data class Connected(
            val address: ServerAddress,
            /** The desktop's device id, from its `helloAck` (used to recognise it in discovery). */
            val peerId: String,
            val peerName: String,
            val session: TransferSession,
            /** False if the desktop app is too old for file transfer. */
            val transferEnabled: Boolean,
            /** True over TLS with a paired desktop; false over plaintext (development only). */
            val secure: Boolean,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Disconnected())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _offers = MutableStateFlow<List<PendingOffer>>(emptyList())
    val offers: StateFlow<List<PendingOffer>> = _offers.asStateFlow()

    /** Transfers from earlier connections, kept after disconnecting. */
    private val _history = MutableStateFlow<List<TransferProgress>>(emptyList())
    val history: StateFlow<List<TransferProgress>> = _history.asStateFlow()

    private var job: Job? = null
    private var channel: OkHttpFrameChannel? = null

    @Volatile
    private var userDisconnected = false

    private val decider = OfferDecider { offer ->
        val pending = PendingOffer(offer)
        _offers.update { it + pending }
        try {
            pending.answer.await()
        } finally {
            _offers.update { it - pending }
        }
    }

    /** Shows the pairing code on screen and waits for the user's answer. */
    private fun pairingUser(address: ServerAddress) = object : PairingUser {
        override suspend fun confirm(code: String): Boolean {
            val answer = CompletableDeferred<Boolean>()
            _state.value = State.ConfirmPairing(address, code, answer)
            return answer.await()
        }

        override fun waitingForDesktop(code: String) {
            _state.value = State.WaitingForDesktop(address, code)
        }
    }

    /**
     * Connects unless already connecting or connected. [desktopId]: the desktop the user picked,
     * if known (from discovery or the last connection); if it is paired, its certificate is pinned.
     */
    fun connect(address: ServerAddress, desktopId: String? = null) {
        if (_state.value !is State.Disconnected) return
        userDisconnected = false
        _state.value = State.Connecting(address)
        val pin = desktopId?.let { paired?.byDeviceId(it)?.certSha256 }
        val transport = TransportConfig.choose(allowPlaintext, pin)
        job = scope.launch {
            when (val result = HelloClient.connect(address, local, transport, tls, paired, pairingUser(address), okHttp)) {
                is ConnectResult.Failed -> _state.value = State.Disconnected(result.reason)
                is ConnectResult.Opened -> runSession(address, result)
            }
        }
    }

    private suspend fun runSession(address: ServerAddress, opened: ConnectResult.Opened) {
        val ack = opened.ack
        channel = opened.channel
        val certSha256 = opened.desktopCertSha256
        if (certSha256 != null) {
            if (opened.pairedNow) {
                paired?.add(PairedDevice(ack.deviceId, ack.deviceName, certSha256, Instant.now().epochSecond))
            } else {
                paired?.rename(certSha256, ack.deviceName)
            }
        }
        val transferEnabled = TransferSession.agreed(local.features, ack.features)
        val session = TransferSession(
            channel = opened.channel,
            peerName = ack.deviceName,
            transferEnabled = transferEnabled,
            sendChunkBytes = TransferSession.sendChunkBytes(local.maxChunkBytes, ack.maxChunkBytes),
            receiveChunkBytes = local.maxChunkBytes,
            sink = sink,
            decider = decider,
        )
        _state.value = State.Connected(address, ack.deviceId, ack.deviceName, session, transferEnabled, secure = certSha256 != null)
        var reason: String? = null
        try {
            reason = session.run()
        } finally {
            opened.channel.close(1000, "bye")
            channel = null
            _history.update { it + session.transfers.value }
            // Show the real cause (e.g. a ping timeout), not just "connection lost".
            _state.value = State.Disconnected(
                if (userDisconnected) null else "connection lost" + (reason?.let { ": $it" } ?: ""),
            )
        }
    }

    /** Disconnects, or cancels connecting or pairing. */
    fun disconnect() {
        userDisconnected = true
        channel?.close(1000, "bye")
        job?.cancel()
        if (_state.value !is State.Connected) _state.value = State.Disconnected()
    }
}
