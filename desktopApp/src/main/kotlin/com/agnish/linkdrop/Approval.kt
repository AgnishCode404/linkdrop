package com.agnish.linkdrop

import kotlinx.coroutines.CompletableDeferred

/** What the desktop's user decided about a phone that is not paired (yet). */
enum class Approval { Allowed, Declined, Busy }

/** Asks the user whether a phone may connect. */
fun interface Approver {
    /** [code] is the pairing code (null over plaintext, where nothing can be verified or remembered). */
    suspend fun approve(deviceName: String, address: String, code: String?): Approval
}

/** A phone waiting in the desktop's "wants to connect" dialog. */
class ConnectionRequest(val deviceName: String, val address: String, val code: String?) {
    internal val answer = CompletableDeferred<Boolean>()

    fun allow() {
        answer.complete(true)
    }

    fun decline() {
        answer.complete(false)
    }
}
