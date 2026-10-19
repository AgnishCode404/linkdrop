package com.agnish.linkdrop

import com.agnish.linkdrop.protocol.Fingerprints
import com.agnish.linkdrop.protocol.PairedDevice
import com.agnish.linkdrop.protocol.PairedDevices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Date

/** Where LinkDrop keeps its own data: `$XDG_DATA_HOME/linkdrop`, usually `~/.local/share/linkdrop`. */
fun linkDropDataDir(
    home: Path = Path.of(System.getProperty("user.home")),
    xdgDataHome: String? = System.getenv("XDG_DATA_HOME"),
): Path = (xdgDataHome?.takeIf { it.startsWith("/") }?.let { Path.of(it) } ?: home.resolve(".local/share")).resolve("linkdrop")

/** The identity file exists but cannot be read. It is never replaced automatically. */
class IdentityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * This desktop's TLS identity: an EC P-256 key and a self-signed certificate, created on first
 * run and kept in `identity.p12` (readable only by the user, like an SSH key). Phones pin its
 * fingerprint when pairing, so it must never change by accident.
 */
class DesktopIdentity private constructor(val privateKey: PrivateKey, val certificate: X509Certificate) {

    val fingerprint: String = Fingerprints.of(certificate.encoded)

    companion object {
        const val FILE_NAME = "identity.p12"
        private const val ALIAS = "linkdrop"

        // The file is protected by its permissions; PKCS12 itself requires some password.
        private val STORE_PASSWORD = "linkdrop".toCharArray()
        private val VALIDITY = Duration.ofDays(365L * 30)

        /**
         * Loads the identity from [dir], or creates it if there is none. Throws
         * [IdentityException] if the file exists but cannot be read: creating a new identity
         * then would make every paired phone reject this desktop.
         */
        fun loadOrCreate(dir: Path, log: (String) -> Unit = {}): DesktopIdentity {
            secureDirectory(dir)
            val file = dir.resolve(FILE_NAME)
            if (Files.exists(file)) return load(file, log)
            val identity = create()
            save(identity, file)
            log("Created this desktop's identity (${Fingerprints.short(identity.fingerprint)})")
            return identity
        }

        private fun load(file: Path, log: (String) -> Unit): DesktopIdentity {
            if (tighten(file, ownerOnlyFile)) log("Fixed permissions of $file (it was readable by others)")
            try {
                val store = KeyStore.getInstance("PKCS12")
                Files.newInputStream(file).use { store.load(it, STORE_PASSWORD) }
                val key = store.getKey(ALIAS, STORE_PASSWORD) as? PrivateKey
                val certificate = store.getCertificate(ALIAS) as? X509Certificate
                if (key == null || certificate == null) throw IdentityException("$file has no LinkDrop identity in it")
                return DesktopIdentity(key, certificate)
            } catch (e: IdentityException) {
                throw e
            } catch (e: Exception) {
                throw IdentityException(
                    "$file cannot be read (${e.message}). It was not replaced: paired phones would no longer " +
                        "recognise this desktop. Restore it from a backup, or delete it to create a new identity " +
                        "and pair all phones again.",
                    e,
                )
            }
        }

        private fun create(): DesktopIdentity {
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val now = Instant.now()
            val name = X500Name("CN=LinkDrop desktop")
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger(127, SecureRandom()).add(BigInteger.ONE), // positive, random serial
                Date.from(now.minus(Duration.ofDays(1))), // tolerate a phone clock that is a little behind
                Date.from(now.plus(VALIDITY)),
                name,
                keys.public,
            )
                .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            // Signed with the JDK's own ECDSA; BouncyCastle only assembles the certificate.
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keys.private)
            val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return DesktopIdentity(keys.private, certificate)
        }

        private fun save(identity: DesktopIdentity, file: Path) {
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(ALIAS, identity.privateKey, STORE_PASSWORD, arrayOf(identity.certificate))
            }
            writeOwnerOnly(file) { out -> store.store(out, STORE_PASSWORD) }
        }
    }
}

/**
 * The phones this desktop has paired with, kept in `paired-phones.json` (owner-only). Changes
 * are written immediately. [devices] is what the window shows.
 */
class PairedPhones(private val file: Path, private val log: (String) -> Unit = {}) {
    private var paired: PairedDevices = load()
    private val _devices = MutableStateFlow(paired.all)
    val devices: StateFlow<List<PairedDevice>> = _devices.asStateFlow()

    @Synchronized
    fun trusted(deviceId: String, certSha256: String): PairedDevice? = paired.trusted(deviceId, certSha256)

    @Synchronized
    fun add(device: PairedDevice) = change { paired.add(device) }

    @Synchronized
    fun rename(certSha256: String, name: String) {
        if (paired.all.any { it.certSha256 == certSha256 && it.name != name }) change { paired.rename(certSha256, name) }
    }

    @Synchronized
    fun forget(certSha256: String) = change { paired.forget(certSha256) }

    private fun change(edit: () -> Unit) {
        edit()
        val text = paired.encode()
        writeOwnerOnly(file) { it.write(text.encodeToByteArray()) }
        _devices.value = paired.all
    }

    private fun load(): PairedDevices {
        if (!Files.exists(file)) return PairedDevices()
        tighten(file, ownerOnlyFile)
        val text = runCatching { Files.readString(file) }.getOrElse {
            log("Could not read $file (${it.message}); starting with no paired phones")
            return PairedDevices()
        }
        val decoded = PairedDevices.decode(text)
        if (decoded.all.isEmpty() && text.isNotBlank() && text.trim() != PairedDevices().encode()) {
            log("$file is damaged; phones must be paired again")
        }
        return decoded
    }

    companion object {
        const val FILE_NAME = "paired-phones.json"
    }
}

private val ownerOnlyDirectory = PosixFilePermissions.fromString("rwx------")
private val ownerOnlyFile = PosixFilePermissions.fromString("rw-------")

/** Creates [dir] if needed and makes it owner-only (it holds the private key). */
private fun secureDirectory(dir: Path) {
    if (!Files.exists(dir)) {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(ownerOnlyDirectory))
    }
    tighten(dir, ownerOnlyDirectory)
}

/** Sets [wanted] permissions if the current ones differ. Returns true if it changed anything. */
private fun tighten(path: Path, wanted: Set<PosixFilePermission>): Boolean {
    val current = runCatching { Files.getPosixFilePermissions(path) }.getOrNull() ?: return false
    if (current == wanted) return false
    Files.setPosixFilePermissions(path, wanted)
    return true
}

/** Writes [file] as owner-only, all at once: a crash mid-write never leaves a half-written file. */
private fun writeOwnerOnly(file: Path, write: (java.io.OutputStream) -> Unit) {
    val dir = file.parent
    secureDirectory(dir)
    val temp = Files.createTempFile(dir, ".${file.fileName}", ".tmp", PosixFilePermissions.asFileAttribute(ownerOnlyFile))
    try {
        Files.newOutputStream(temp).use(write)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temp)
    }
}
