package com.mazeconnect.core.transport

import com.mazeconnect.core.crypto.Sas
import com.mazeconnect.core.pairing.PairedDeviceStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Authenticates a peer by its pinned public key, not by a certificate chain.
 *
 * Both clients present self-signed certificates, so the platform's CA
 * validation is meaningless here and is replaced entirely by this check.
 * That substitution is only safe because this class never returns without
 * either matching a pinned key or throwing — there is no path where an
 * unverified peer is quietly accepted.
 *
 * [allowUnpaired] exists solely for the pairing handshake, where the peer's
 * key is by definition not yet known. Even then nothing is persisted: the
 * key still has to survive the user's SAS comparison before it is pinned.
 */
class PinnedTrustManager(
    private val store: PairedDeviceStore,
    private val allowUnpaired: Boolean,
) : X509TrustManager {

    /** The key the peer actually presented, available after the handshake. */
    @Volatile
    var peerPublicKey: ByteArray? = null
        private set

    /** True when the presented key was already pinned. */
    @Volatile
    var peerWasTrusted: Boolean = false
        private set

    /**
     * Populate from a handshake this instance did not take part in.
     *
     * An `SSLServerSocket` is built from one `SSLContext` and every socket it
     * accepts handshakes through *that* context's trust manager. A per-
     * connection manager created beside the accept loop therefore never sees
     * a certificate, and its [peerPublicKey] stays null forever — which is
     * not a degraded link but no link at all, since Connection refuses to
     * start without a peer key.
     *
     * So the accept path hands the completed session's chain here instead.
     * The chain comes from `SSLSession.getPeerCertificates()`, which the
     * platform only populates once the peer has actually proved possession
     * of that key, and it is then run through exactly the same [check] as a
     * live handshake: same EC-only rule, same plausibility test, same
     * pinning decision. Nothing is trusted here that would not have been
     * trusted there.
     *
     * @return false if the chain is unusable or, for a paired-mode manager,
     *         not pinned — the caller must drop the connection.
     */
    fun adoptCompletedHandshake(chain: Array<out X509Certificate>?): Boolean =
        runCatching { check(chain) }.isSuccess

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        check(chain)

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("peer presented no certificate")

        val publicKey = leaf.publicKey
        // Anything but EC is outside this protocol; refuse rather than
        // accommodate a key type the rest of the stack does not expect.
        if (publicKey.algorithm != "EC") {
            throw CertificateException("peer key is ${publicKey.algorithm}, expected EC")
        }

        val encoded = publicKey.encoded
            ?: throw CertificateException("peer key could not be encoded")
        if (!Sas.isPlausiblePublicKey(encoded)) {
            throw CertificateException("peer key is malformed")
        }

        peerPublicKey = encoded
        peerWasTrusted = store.isTrusted(encoded)

        if (!peerWasTrusted && !allowUnpaired) {
            // Hard fail. Never fall back to prompting here: a mismatch on an
            // established pairing is exactly what an attacker looks like, and
            // re-prompting would train the habit that makes pinning worthless.
            throw CertificateException("peer key is not paired")
        }
    }

    // Empty by design: we are not a CA client and accept no issuers.
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
