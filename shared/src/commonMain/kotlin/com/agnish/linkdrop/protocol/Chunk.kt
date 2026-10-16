package com.agnish.linkdrop.protocol

/** Size of the `fileId` header at the start of every binary frame. */
const val CHUNK_HEADER_BYTES = 4

/**
 * One binary frame of file data: `[fileId: u32 big-endian][payload]` (PROTOCOL.md, "Binary frames").
 * [fileId] is in 0..Int.MAX_VALUE, matching the JSON `fileId`.
 */
class Chunk(val fileId: Int, val payload: ByteArray) {
    companion object {
        /** Builds a frame from `data[offset until offset + length]`. */
        fun encode(fileId: Int, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
            require(fileId >= 0) { "fileId must not be negative" }
            require(length > 0) { "a chunk carries at least one byte" }
            val frame = ByteArray(CHUNK_HEADER_BYTES + length)
            frame[0] = (fileId ushr 24).toByte()
            frame[1] = (fileId ushr 16).toByte()
            frame[2] = (fileId ushr 8).toByte()
            frame[3] = fileId.toByte()
            data.copyInto(frame, CHUNK_HEADER_BYTES, offset, offset + length)
            return frame
        }

        /** Returns null if [frame] has no payload or a fileId above Int.MAX_VALUE. */
        fun decode(frame: ByteArray): Chunk? {
            if (frame.size <= CHUNK_HEADER_BYTES) return null
            val fileId = ((frame[0].toInt() and 0xff) shl 24) or
                ((frame[1].toInt() and 0xff) shl 16) or
                ((frame[2].toInt() and 0xff) shl 8) or
                (frame[3].toInt() and 0xff)
            if (fileId < 0) return null
            return Chunk(fileId, frame.copyOfRange(CHUNK_HEADER_BYTES, frame.size))
        }
    }
}
