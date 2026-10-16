package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.LocalDevice
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

/** An offer shown on screen until the user answers (or it times out). */
class PendingOffer(val offer: IncomingOffer) {
    val answer = CompletableDeferred<Boolean>()
}

/**
 * The phone's connection to one desktop. It lives as long as the app process (see
 * [LinkDropApplication]), so rotating the screen or reopening the activity does not drop it.
 */
class PhoneConnection(
    private val local: LocalDevice,
    private val sink: FileSink,
    private val scope: CoroutineScope,
    private val okHttp: OkHttpClient = HelloClient.okHttp,
) {
    sealed interface State {
        /** [reason] is null when the user disconnected (or never connected). */
        data class Disconnected(val reason: String? = null) : State
        data class Connecting(val address: ServerAddress) : State
        data class Connected(
            val address: ServerAddress,
            val peerName: String,
            val session: TransferSession,
            /** False if the desktop app is too old for file transfer. */
            val transferEnabled: Boolean,
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

    /** Connects unless already connecting or connected. */
    fun connect(address: ServerAddress, transport: TransportConfig) {
        if (_state.value !is State.Disconnected) return
        userDisconnected = false
        _state.value = State.Connecting(address)
        job = scope.launch {
            when (val result = HelloClient.connect(address, local, transport, okHttp)) {
                is ConnectResult.Failed -> _state.value = State.Disconnected(result.reason)
                is ConnectResult.Opened -> runSession(address, result)
            }
        }
    }

    private suspend fun runSession(address: ServerAddress, opened: ConnectResult.Opened) {
        val ack = opened.ack
        channel = opened.channel
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
        _state.value = State.Connected(address, ack.deviceName, session, transferEnabled)
        try {
            session.run()
        } finally {
            opened.channel.close(1000, "bye")
            channel = null
            _history.update { it + session.transfers.value }
            _state.value = State.Disconnected(if (userDisconnected) null else "connection lost")
        }
    }

    fun disconnect() {
        userDisconnected = true
        channel?.close(1000, "bye")
        job?.cancel()
        if (_state.value is State.Connecting) _state.value = State.Disconnected()
    }
}
