package com.agnish.linkdrop.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PartialsTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun record(keptAt: Long = now, bytes: Long = 10, size: Long = 100) =
        PartialRecord("a".repeat(64), "photo.jpg", size, bytes, keptAt)

    @Test
    fun recordRoundTrips() {
        val record = record()
        assertEquals(record, Partials.decode(Partials.encode(record)))
    }

    @Test
    fun brokenOrNonsenseRecordsAreRejected() {
        assertNull(Partials.decode("not json"))
        assertNull(Partials.decode("{}"))
        assertNull(Partials.decode(Partials.encode(record(bytes = 0))), "nothing received")
        assertNull(Partials.decode(Partials.encode(record(bytes = 100))), "complete: nothing to resume")
        assertNull(Partials.decode(Partials.encode(record(bytes = 101))), "more than the file")
        assertNull(Partials.decode(Partials.encode(record().copy(peer = " "))))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = Partials.encode(record()).replaceFirst("{", "{\"later\":1,")
        assertEquals(record(), Partials.decode(text))
    }

    @Test
    fun expiredRecordsAreNotKept() {
        val records = mapOf(
            "fresh" to record(keptAt = now - day),
            "almost" to record(keptAt = now - Partials.MAX_AGE_MILLIS),
            "old" to record(keptAt = now - Partials.MAX_AGE_MILLIS - 1),
        )
        assertEquals(setOf("fresh", "almost"), Partials.toKeep(records, now))
    }

    @Test
    fun recordsFromFarInTheFutureAreNotKept() {
        // The clock was moved back: such a record would otherwise never expire.
        val records = mapOf("soon" to record(keptAt = now + 60_000), "future" to record(keptAt = now + 2 * day))
        assertEquals(setOf("soon"), Partials.toKeep(records, now))
    }

    @Test
    fun onlyTheNewestAreKept() {
        val records = (0 until Partials.MAX_KEPT + 4).associateWith { record(keptAt = now - it * 1000L) }
        assertEquals((0 until Partials.MAX_KEPT).toSet(), Partials.toKeep(records, now))
    }
}
