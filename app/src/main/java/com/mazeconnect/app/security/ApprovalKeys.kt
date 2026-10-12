package com.mazeconnect.app.security

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This phone's approval key: what unlocks a computer and approves its sudo.
 *
 * Separate from the identity key that carries the TLS link, on purpose. That
 * one has to sign every handshake with nobody present; this one signs
 * **only** after the owner's fingerprint (or, from Android 11, the screen-lock
 * PIN) for that one signature:
 *
 *  - generated inside the Keystore — StrongBox where the phone has one —
 *    and never exportable;
 *  - `setUserAuthenticationRequired` with a zero timeout, so each signature
 *    needs its own authentication, bound to it through BiometricPrompt's
 *    CryptoObject (an authentication a moment earlier does not count);
 *  - invalidated when a fingerprint is added, so someone who learns the PIN
 *    and enrols a finger of their own gets a dead key, not a working one;
 *  - usable only while the phone is unlocked.
 *
 * The computer sees the public half and nothing else.
 */
object ApprovalKeys {
    private const val ALIAS = "maze-connect-approval"
    private const val PROVIDER = "AndroidKeyStore"

    /**
     * What BiometricPrompt is asked for from Android 11, matching how the key
     * was made. Before that the prompt is fingerprint-only with a Cancel
     * button, which is all a CryptoObject could be used with there.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    const val AUTHENTICATORS: Int =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** The public key (SPKI DER), or null when approvals are not set up. */
    fun publicKey(): ByteArray? = runCatching {
        keyStore().getCertificate(ALIAS)?.publicKey?.encoded
    }.getOrNull()

    /**
     * Why this phone cannot hold an approval key right now, or null if it can.
     * Checked before creating one, so "set up" never makes a key that no
     * prompt could ever unlock.
     */
    fun unavailableReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 has no BiometricManager. A screen lock is the least
            // it needs; with no fingerprint enrolled, creating the key fails
            // in create() and is reported from there.
            val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
            return if (keyguard?.isDeviceSecure == true) null
            else "set up a screen lock and a fingerprint on this phone first"
        }
        val manager = context.getSystemService(BiometricManager::class.java)
            ?: return "this phone has no biometric support"
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(AUTHENTICATORS)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "set up a fingerprint (or a screen lock) on this phone first"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                "this phone's biometric hardware is not available"
            else -> "this phone cannot ask for a fingerprint right now"
        }
    }

    /** Make the key (replacing any old one) and return its public half. */
    fun create(): ByteArray {
        delete()
        return try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): ByteArray {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // 0: every use needs its own authentication.
                    setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(spec)
        return generator.generateKeyPair().public.encoded
    }

    fun delete() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    /**
     * A Signature ready to sign once BiometricPrompt has authenticated it.
     * Throws KeyPermanentlyInvalidatedException if fingerprints changed since
     * the key was made — the caller removes the key and says so.
     */
    fun signature(): Signature {
        val key = keyStore().getKey(ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("approvals are not set up")
        return Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
    }
}
