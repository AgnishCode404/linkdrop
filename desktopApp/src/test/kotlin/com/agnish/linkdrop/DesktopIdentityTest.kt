package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Fingerprints
import com.agnish.linkdrop.protocol.PairedDevice
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopIdentityTest {

    private val root: Path = createTempDirectory("linkdrop-identity")
    private val dir: Path = root.resolve("data/linkdrop")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun permissions(path: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun identityIsCreatedOnceAndThenReused() {
        val log = mutableListOf<String>()
        val first = DesktopIdentity.loadOrCreate(dir) { log += it }
        val second = DesktopIdentity.loadOrCreate(dir) { log += it }

        assertEquals(first.fingerprint, second.fingerprint, "a restart must keep the same identity")
        assertEquals(first.privateKey.encoded.toList(), second.privateKey.encoded.toList())
        assertEquals(1, log.count { it.startsWith("Created") })
        assertTrue(Fingerprints.isValid(first.fingerprint))
    }

    @Test
    fun certificateIsSelfSignedEcP256AndLongLived() {
        val identity = DesktopIdentity.loadOrCreate(dir)
        val cert = identity.certificate
        cert.verify(cert.publicKey) // self-signed: its own key verifies it
        assertEquals(256, (cert.publicKey as ECPublicKey).params.order.bitLength())
        assertEquals("CN=LinkDrop desktop", cert.subjectX500Principal.name)
        assertTrue(cert.notAfter.toInstant().isAfter(Instant.now().plus(Duration.ofDays(365L * 29))))
        assertTrue(cert.notBefore.toInstant().isBefore(Instant.now()))
        assertEquals(-1, cert.basicConstraints, "must not be a CA certificate")

        // The stored private key belongs to the certificate.
        val data = "pairing".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(identity.privateKey); update(data); sign() }
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(cert); update(data); verify(signature) })
    }

    @Test
    fun twoDesktopsGetDifferentIdentities() {
        val a = DesktopIdentity.loadOrCreate(root.resolve("a"))
        val b = DesktopIdentity.loadOrCreate(root.resolve("b"))
        assertNotEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun filesAreReadableOnlyByTheOwner() {
        DesktopIdentity.loadOrCreate(dir)
        assertEquals("rwx------", permissions(dir))
        assertEquals("rw-------", permissions(dir.resolve(DesktopIdentity.FILE_NAME)))
        // No temporary files left behind.
        assertEquals(listOf(DesktopIdentity.FILE_NAME), Files.list(dir).map { it.fileName.toString() }.toList())
    }

    @Test
    fun loosePermissionsAreTightenedOnLoad() {
        DesktopIdentity.loadOrCreate(dir)
        val file = dir.resolve(DesktopIdentity.FILE_NAME)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        val log = mutableListOf<String>()

        DesktopIdentity.loadOrCreate(dir) { log += it }
        assertEquals("rw-------", permissions(file))
        assertEquals("rwx------", permissions(dir))
        assertTrue(log.any { "Fixed permissions" in it }, "$log")
    }

    @Test
    fun aDamagedIdentityIsReportedAndNeverReplaced() {
        val original = DesktopIdentity.loadOrCreate(dir)
        val file = dir.resolve(DesktopIdentity.FILE_NAME)
        val damaged = original.certificate.encoded.copyOf(100) // not a PKCS12 file
        file.writeBytes(damaged)

        val error = assertFailsWith<IdentityException> { DesktopIdentity.loadOrCreate(dir) }
        assertTrue("not replaced" in error.message!!, error.message)
        assertContentEquals(damaged, file.readBytes(), "the damaged file must be left as it was")
    }

    @Test
    fun dataDirFollowsXdg() {
        val home = Path.of("/home/someone")
        assertEquals(Path.of("/home/someone/.local/share/linkdrop"), linkDropDataDir(home, null))
        assertEquals(Path.of("/data/xdg/linkdrop"), linkDropDataDir(home, "/data/xdg"))
        // A relative XDG_DATA_HOME is invalid per the XDG spec and ignored.
        assertEquals(Path.of("/home/someone/.local/share/linkdrop"), linkDropDataDir(home, "relative/path"))
    }

    private val fpA = "a".repeat(64)
    private val fpB = "b".repeat(64)

    @Test
    fun pairedPhonesSurviveARestart() {
        val file = dir.resolve(PairedPhones.FILE_NAME)
        val phones = PairedPhones(file)
        phones.add(PairedDevice("phone-1", "Pixel", fpA, 1))
        phones.add(PairedDevice("phone-2", "Tablet", fpB, 2))
        phones.rename(fpA, "Pixel 9")
        phones.forget(fpB)

        val reloaded = PairedPhones(file)
        assertEquals("Pixel 9", reloaded.trusted("phone-1", fpA)?.name)
        assertNull(reloaded.trusted("phone-2", fpB))
        assertEquals(listOf("phone-1"), reloaded.devices.value.map { it.deviceId })
        assertEquals("rw-------", permissions(file))
    }

    @Test
    fun aDamagedPairedPhonesFileMeansPairingAgainNotACrash() {
        val file = dir.resolve(PairedPhones.FILE_NAME)
        Files.createDirectories(dir)
        file.writeText("{ this is not json")
        val log = mutableListOf<String>()

        val phones = PairedPhones(file) { log += it }
        assertTrue(phones.devices.value.isEmpty())
        assertTrue(log.any { "damaged" in it }, "$log")
        // The next pairing replaces the damaged file with a valid one.
        phones.add(PairedDevice("phone-1", "Pixel", fpA, 1))
        assertEquals("phone-1", PairedPhones(file).trusted("phone-1", fpA)?.deviceId)
    }
}
