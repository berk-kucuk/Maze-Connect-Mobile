package com.mazeconnect.core.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * This device's long-term identity, held in the Android Keystore.
 *
 * The private key is generated *inside* the Keystore and is never
 * exportable: this class can ask it to sign, but cannot read it, and neither
 * can anything else in the process. On devices with a TEE or StrongBox the
 * key never enters application memory at all.
 *
 * This is the reason the protocol uses EC P-256 rather than Ed25519.
 * Android's Keystore hardware-backs P-256 on essentially every shipping
 * device; Ed25519 is not hardware-backed, so choosing it would have meant
 * keeping the private key in app memory — a much worse trade than any
 * difference between the two curves.
 *
 * The public key is published as SubjectPublicKeyInfo DER
 * (`PublicKey.getEncoded()`), which is byte-identical to what OpenSSL's
 * `i2d_PUBKEY()` produces on the desktop side. That shared encoding is what
 * lets both clients derive the same fingerprint and the same pairing code
 * with no custom serialisation to keep in sync.
 */
class AndroidKeyStoreManager {

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Create the identity key if this is the first run. */
    fun ensureIdentity(): Boolean = try {
        if (!keyStore.containsAlias(KEY_ALIAS)) generateIdentity()
        keyStore.containsAlias(KEY_ALIAS)
    } catch (e: Exception) {
        Log.e(TAG, "could not establish an identity key", e)
        false
    }

    /** True once an identity exists. */
    fun hasIdentity(): Boolean = runCatching { keyStore.containsAlias(KEY_ALIAS) }.getOrDefault(false)

    /** SubjectPublicKeyInfo DER — the bytes peers pin. */
    fun publicKey(): ByteArray? = runCatching {
        (keyStore.getCertificate(KEY_ALIAS) as? X509Certificate)?.publicKey?.encoded
    }.getOrNull()

    /** The self-signed certificate presented during the TLS handshake. */
    fun certificate(): X509Certificate? = runCatching {
        keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
    }.getOrNull()

    /**
     * Handle to the private key. The key material itself never leaves the
     * Keystore; this only permits signing operations.
     */
    fun privateKey(): PrivateKey? = runCatching {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.privateKey
    }.getOrNull()

    /**
     * Delete the identity. Every existing pairing becomes invalid, so this
     * must only ever be reached through an explicit, confirmed user action.
     */
    fun wipeIdentity(): Boolean = runCatching {
        keyStore.deleteEntry(KEY_ALIAS)
        true
    }.getOrDefault(false)

    private fun generateIdentity() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            // DIGEST_NONE is required, not optional: Conscrypt hashes the
            // TLS handshake transcript itself and asks the Keystore to sign
            // the already-computed digest (NONEwithECDSA). A key restricted
            // to SHA-256 alone makes every handshake fail with
            // "Incompatible digest", which is exactly what happened before
            // this was tested on a real device.
            //
            // Permitting NONE does not weaken ECDSA here: the key is
            // non-exportable and usable only by this app, and pre-hashed
            // signing is how every TLS stack drives a hardware-backed key.
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            // Not tied to screen lock: the link must survive the screen
            // turning off, and gating every handshake on authentication
            // would make the app unusable. Sensitive *actions* — re-pairing,
            // removing a device, wiping the identity — are what carry a
            // BiometricPrompt instead.
            .setUserAuthenticationRequired(false)
            .build()

        generator.initialize(spec)
        generator.generateKeyPair()
    }

    companion object {
        private const val TAG = "MazeKeyStore"
        private const val PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "maze-connect-identity"
    }
}
