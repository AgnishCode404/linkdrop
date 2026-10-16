package com.agnish.linkdrop.transfer

/** What the user sees for one file. */
sealed interface FileStatus {
    data object Waiting : FileStatus
    data class Transferring(val bytes: Long) : FileStatus
    /** [savedAs] is where the receiver stored the file (null on the sending side). */
    data class Done(val savedAs: String? = null) : FileStatus
    data class Failed(val reason: String) : FileStatus
    data object Declined : FileStatus
    data object Cancelled : FileStatus

    val isFinal: Boolean get() = this !is Waiting && this !is Transferring
}

data class FileProgress(val fileId: Int, val name: String, val size: Long, val status: FileStatus)

enum class Direction { Outgoing, Incoming }

/** One transfer (an offer of one or more files) in either direction. */
data class TransferProgress(
    val transferId: String,
    val direction: Direction,
    val files: List<FileProgress>,
    /** Why the transfer ended early, if it did (e.g. "not enough free space"). */
    val note: String? = null,
) {
    val finished: Boolean get() = files.all { it.status.isFinal }
}

/** An offer waiting for the user's decision. [files] are only the files that can be accepted. */
data class IncomingOffer(val transferId: String, val peerName: String, val files: List<FileProgress>) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

/** Asks the user about an offer. Returns true to accept all of [IncomingOffer.files]. */
fun interface OfferDecider {
    suspend fun decide(offer: IncomingOffer): Boolean
}
