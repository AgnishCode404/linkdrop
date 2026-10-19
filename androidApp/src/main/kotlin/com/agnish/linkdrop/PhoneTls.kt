package com.agnish.linkdrop

import android.net.ssl.SSLSockets
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.agnish.linkdrop.protocol.Fingerprints
import com.agnish.linkdrop.protocol.Pairing
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal

/** The phone's TLS identity: a private key and its self-signed certificate. */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: String = Fingerprints.of(certificate.encoded)
}

/**
 * The phone's identity in the Android Keystore: created on first use, then kept. The private key
 * cannot leave the phone (it lives in the Keystore, hardware-backed where available). Reinstalling
 * the app creates a new identity, and desktops then ask to pair again.
 */
object PhoneIdentity {
    private const val ALIAS = "linkdrop-identity"
    private const val YEARS = 30L

    @Synchronized
    fun loadOrCreate(): ClientIdentity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(ALIAS, null) as? PrivateKey
        val certificate = store.getCertificate(ALIAS) as? X509Certificate
        if (key != null && certificate != null) return ClientIdentity(key, certificate)

        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS 1.3 signs with ECDSA-SHA256; NONE lets the TLS library hash itself.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=LinkDrop phone"))
            .setCertificateSerialNumber(BigInteger(127, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - 24L * 3600 * 1000))
            .setCertificateNotAfter(Date(now + YEARS * 365 * 24 * 3600 * 1000))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        store.load(null)
        return ClientIdentity(store.getKey(ALIAS, null) as PrivateKey, store.getCertificate(ALIAS) as X509Certificate)
    }
}

/** Presents [identity] as the TLS client certificate (the desktop requires one). */
class IdentityKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?) = ALIAS
    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(ALIAS)
    override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?) = identity.privateKey
    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null

    private companion object {
        const val ALIAS = "linkdrop"
    }
}

/**
 * Checks the desktop's certificate during the TLS handshake. With [pinnedCertSha256], any other
 * certificate is refused before a single byte of data is exchanged. Without (pairing), any
 * certificate is accepted here and recorded: the users' code comparison decides.
 *
 * As an X509ExtendedTrustManager it is also handed the TLS socket, which the pairing code needs.
 */
class DesktopTrust(private val pinnedCertSha256: String?) : X509ExtendedTrustManager() {
    @Volatile
    var seenCertSha256: String? = null
        private set

    @Volatile
    var socket: SSLSocket? = null
        private set

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        this.socket = socket as? SSLSocket
        checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val certificate = chain.firstOrNull() ?: throw CertificateException("no desktop certificate")
        val fingerprint = Fingerprints.of(certificate.encoded)
        seenCertSha256 = fingerprint
        if (pinnedCertSha256 != null && fingerprint != pinnedCertSha256) throw ChangedIdentityException()
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = refuse()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = refuse()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = refuse()
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun refuse(): Nothing = throw CertificateException("the phone is never a TLS server")
}

/** The paired desktop presented a different certificate than the one pinned when pairing. */
class ChangedIdentityException : CertificateException("the desktop's certificate does not match the pairing")

/** Reads the TLS session's keying material for the pairing code (RFC 8446 section 7.5). */
fun interface KeyingMaterialExporter {
    fun export(socket: SSLSocket): ByteArray
}

/** Android's own exporter (API 31+, hence minSdk 31), for sockets of the platform TLS stack (which OkHttp uses). */
object AndroidKeyingMaterialExporter : KeyingMaterialExporter {
    override fun export(socket: SSLSocket): ByteArray {
        if (!SSLSockets.isSupportedSocket(socket)) throw SSLException("this TLS socket cannot export a pairing code")
        return SSLSockets.exportKeyingMaterial(socket, Pairing.EXPORTER_LABEL, Pairing.EXPORTER_CONTEXT, Pairing.EXPORTER_LENGTH)
            ?: throw SSLException("the TLS session gave no pairing code")
    }
}

/** What the phone needs to make TLS connections: who it is, and how it reads pairing codes. */
class PhoneTls(val identity: () -> ClientIdentity, val exporter: KeyingMaterialExporter)
