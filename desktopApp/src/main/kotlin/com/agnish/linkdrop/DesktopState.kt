package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Pairing
import com.agnish.linkdrop.transfer.IncomingOffer
import com.agnish.linkdrop.transfer.OfferDecider
import com.agnish.linkdrop.transfer.TransferProgress
import com.agnish.linkdrop.transfer.TransferSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One connected phone. */
class PeerConnection(
    val name: String,
    val address: String,
    val session: TransferSession,
    /** False if the phone's app is too old for file transfer. */
    val transferEnabled: Boolean,
)

/** An offer shown in the window until the user answers (or it times out). */
class PendingOffer(val offer: IncomingOffer) {
    val answer = CompletableDeferred<Boolean>()
}

/** A finished transfer kept after its phone disconnected. */
data class PastTransfer(val peerName: String, val progress: TransferProgress)

/**
 * What the desktop window shows. Updated from server threads; read by the UI.
 * [approvalTimeout]: how long a "wants to connect" dialog waits before it counts as Decline.
 */
class DesktopState(private val approvalTimeout: Duration = Pairing.CONFIRM_TIMEOUT_SECONDS.seconds) {
    private val _request = MutableStateFlow<ConnectionRequest?>(null)

    /** The phone currently asking to connect, shown as a dialog. At most one at a time. */
    val request: StateFlow<ConnectionRequest?> = _request.asStateFlow()

    /**
     * Shows the "wants to connect" dialog. Only one can be open: a second phone asking meanwhile
     * gets [Approval.Busy] at once, so a stranger cannot stack up dialogs.
     */
    val approver = Approver { deviceName, address, code ->
        val request = ConnectionRequest(deviceName, address, code)
        if (!_request.compareAndSet(null, request)) return@Approver Approval.Busy
        try {
            if (withTimeoutOrNull(approvalTimeout) { request.answer.await() } == true) Approval.Allowed else Approval.Declined
        } finally {
            // Also when the phone gave up (this coroutine was cancelled): the dialog disappears.
            _request.compareAndSet(request, null)
        }
    }

    private val _connections = MutableStateFlow<List<PeerConnection>>(emptyList())
    val connections: StateFlow<List<PeerConnection>> = _connections.asStateFlow()

    private val _offers = MutableStateFlow<List<PendingOffer>>(emptyList())
    val offers: StateFlow<List<PendingOffer>> = _offers.asStateFlow()

    private val _history = MutableStateFlow<List<PastTransfer>>(emptyList())
    val history: StateFlow<List<PastTransfer>> = _history.asStateFlow()

    fun connected(connection: PeerConnection) = _connections.update { it + connection }

    fun disconnected(connection: PeerConnection) {
        _connections.update { it - connection }
        _history.update { past -> past + connection.session.transfers.value.map { PastTransfer(connection.name, it) } }
    }

    /** Asks the user in the window. The offer disappears when answered or when the session gives up. */
    val decider = OfferDecider { offer ->
        val pending = PendingOffer(offer)
        _offers.update { it + pending }
        try {
            pending.answer.await()
        } finally {
            _offers.update { it - pending }
        }
    }
}
