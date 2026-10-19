package com.agnish.linkdrop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun codeIsTheExportedNumberModuloAMillion() {
        assertEquals("000000", Pairing.code(bytes(0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals("123456", Pairing.code(bytes(0, 0, 0, 0, 0, 0x01, 0xE2, 0x40)))
        assertEquals("999999", Pairing.code(bytes(0, 0, 0, 0, 0, 0x0F, 0x42, 0x3F)))
        assertEquals("000000", Pairing.code(bytes(0, 0, 0, 0, 0, 0x0F, 0x42, 0x40))) // exactly 1,000,000
        // All bits set: 18446744073709551615 (unsigned, not negative) mod 1,000,000.
        assertEquals("551615", Pairing.code(ByteArray(8) { 0xFF.toByte() }))
    }

    @Test
    fun codeNeedsExactlyEightBytes() {
        assertFailsWith<IllegalArgumentException> { Pairing.code(ByteArray(7)) }
        assertFailsWith<IllegalArgumentException> { Pairing.code(ByteArray(9)) }
    }

    @Test
    fun codeIsShownInTwoGroups() {
        assertEquals("482 913", Pairing.formatCode("482913"))
        assertEquals("000 000", Pairing.formatCode("000000"))
    }

    @Test
    fun fingerprintIsLowercaseSha256Hex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Fingerprints.of("abc".encodeToByteArray()))
        assertTrue(Fingerprints.isValid(Fingerprints.of(byteArrayOf(1))))
        for (bad in listOf("", "abc", "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", "g".repeat(64), "a".repeat(63))) {
            assertFalse(Fingerprints.isValid(bad), bad)
        }
        assertEquals("ba78 16bf 8f01 cfea", Fingerprints.short(Fingerprints.of("abc".encodeToByteArray())))
    }

    private val fpA = "a".repeat(64)
    private val fpB = "b".repeat(64)
    private val fpC = "c".repeat(64)
    private fun device(id: String, fp: String, name: String = "Pixel") = PairedDevice(id, name, fp, pairedAtEpochSeconds = 1_700_000_000)

    @Test
    fun trustedNeedsBothTheCertificateAndTheDeviceId() {
        val paired = PairedDevices(listOf(device("phone-1", fpA)))
        assertEquals("phone-1", paired.trusted("phone-1", fpA)?.deviceId)
        assertNull(paired.trusted("phone-1", fpB), "same id, other certificate (e.g. an impostor)")
        assertNull(paired.trusted("phone-2", fpA), "same certificate, other id")
        assertNull(PairedDevices().trusted("phone-1", fpA))
    }

    @Test
    fun pairingAgainWithANewCertificateReplacesTheOldOne() {
        val paired = PairedDevices(listOf(device("phone-1", fpA), device("phone-2", fpC)))
        paired.add(device("phone-1", fpB)) // e.g. the app was reinstalled
        assertNull(paired.trusted("phone-1", fpA))
        assertEquals("phone-1", paired.trusted("phone-1", fpB)?.deviceId)
        assertEquals(listOf("phone-2", "phone-1"), paired.all.map { it.deviceId })
        assertEquals(fpB, paired.byDeviceId("phone-1")?.certSha256)
    }

    @Test
    fun renameAndForget() {
        val paired = PairedDevices(listOf(device("phone-1", fpA, name = "Pixel")))
        paired.rename(fpA, "Pixel 9")
        assertEquals("Pixel 9", paired.trusted("phone-1", fpA)?.name)
        paired.forget(fpA)
        assertNull(paired.trusted("phone-1", fpA))
        assertTrue(paired.all.isEmpty())
    }

    @Test
    fun invalidFingerprintsAreNeverStored() {
        assertFailsWith<IllegalArgumentException> { PairedDevices().add(device("x", "not-a-fingerprint")) }
    }

    @Test
    fun storedListSurvivesEncodeAndDecode() {
        val paired = PairedDevices(listOf(device("phone-1", fpA), device("phone-2", fpB, name = "Tablet")))
        assertEquals(paired.all, PairedDevices.decode(paired.encode()).all)
    }

    @Test
    fun damagedStorageGivesAnEmptyListNotACrash() {
        for (text in listOf("", "not json", "{\"devices\": 5}", "[1,2,3]")) {
            assertTrue(PairedDevices.decode(text).all.isEmpty(), text)
        }
        val oneBad = """{"devices":[
            {"deviceId":"ok","name":"A","certSha256":"$fpA","pairedAtEpochSeconds":1},
            {"deviceId":"bad","name":"B","certSha256":"xyz","pairedAtEpochSeconds":1}]}"""
        assertEquals(listOf("ok"), PairedDevices.decode(oneBad).all.map { it.deviceId })
    }
}
