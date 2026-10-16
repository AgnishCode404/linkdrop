package com.agnish.linkdrop.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChunkTest {

    @Test
    fun headerIsBigEndianFileId() {
        val frame = Chunk.encode(0x01020304, byteArrayOf(9, 8))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 9, 8), frame)
    }

    @Test
    fun roundTrip() {
        for (fileId in listOf(0, 1, 255, 256, 65_536, Int.MAX_VALUE)) {
            val chunk = assertNotNull(Chunk.decode(Chunk.encode(fileId, byteArrayOf(42, 43, 44))))
            assertEquals(fileId, chunk.fileId)
            assertContentEquals(byteArrayOf(42, 43, 44), chunk.payload)
        }
    }

    @Test
    fun encodesOnlyTheRequestedSlice() {
        val data = byteArrayOf(0, 1, 2, 3, 4, 5)
        assertContentEquals(byteArrayOf(0, 0, 0, 7, 2, 3, 4), Chunk.encode(7, data, offset = 2, length = 3))
    }

    @Test
    fun framesWithoutPayloadOrWithTooLargeFileIdAreRejected() {
        assertNull(Chunk.decode(byteArrayOf()))
        assertNull(Chunk.decode(byteArrayOf(0, 0, 0, 1)))
        // 0x80000000 does not fit in the JSON fileId range (0..Int.MAX_VALUE).
        assertNull(Chunk.decode(byteArrayOf(0x80.toByte(), 0, 0, 0, 1)))
    }

    @Test
    fun invalidEncodeArgumentsAreRefused() {
        assertFailsWith<IllegalArgumentException> { Chunk.encode(-1, byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { Chunk.encode(1, byteArrayOf()) }
    }
}
