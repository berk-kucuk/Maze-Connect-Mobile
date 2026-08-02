package com.mazeconnect.core.transport

import com.mazeconnect.core.Limits
import com.mazeconnect.core.keystore.AndroidKeyStoreManager
import com.mazeconnect.core.pairing.PairedDeviceStore
import java.net.InetAddress
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager

/**
 * Builds mutually-authenticated TLS 1.3 sockets.
 *
 * TLS 1.3 is set as the only enabled protocol rather than merely preferred:
 * with no 1.2 in the list there is no downgrade to negotiate, so there is
 * nothing to defend against later.
 */
class TlsFactory(
    private val keyStoreManager: AndroidKeyStoreManager,
    private val pairedDevices: PairedDeviceStore,
) {

    /**
     * A key manager backed by the Android Keystore.
     *
     * [getPrivateKey] returns a handle, not key material: the private key
     * stays inside the Keystore (and inside the TEE/StrongBox where the
     * device has one) and only ever performs signing there.
     */
    private inner class KeystoreKeyManager : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) =
            arrayOf(ALIAS)

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: java.net.Socket?,
        ) = ALIAS

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
            arrayOf(ALIAS)

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: java.net.Socket?,
        ) = ALIAS

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            keyStoreManager.certificate()?.let { arrayOf(it) }

        override fun getPrivateKey(alias: String?): PrivateKey? = keyStoreManager.privateKey()
    }

    /**
     * @param allowUnpaired only true inside an explicit, user-initiated
     *        pairing flow — never on a normal connection.
     */
    fun createContext(allowUnpaired: Boolean): Pair<SSLContext, PinnedTrustManager> {
        val trustManager = PinnedTrustManager(pairedDevices, allowUnpaired)
        val context = SSLContext.getInstance("TLSv1.3")
        context.init(arrayOf(KeystoreKeyManager()), arrayOf(trustManager), SecureRandom())
        return context to trustManager
    }

    fun connect(
        address: InetAddress,
        port: Int,
        allowUnpaired: Boolean,
    ): Pair<SSLSocket, PinnedTrustManager> {
        val (context, trustManager) = createContext(allowUnpaired)
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.soTimeout = Limits.HANDSHAKE_TIMEOUT_MS
        socket.connect(java.net.InetSocketAddress(address, port), Limits.HANDSHAKE_TIMEOUT_MS)
        harden(socket)
        socket.startHandshake()
        return socket to trustManager
    }

    /**
     * Applied to every socket, inbound and outbound.
     *
     * TLS 1.3 only, and — on the server side — client authentication is
     * required rather than merely requested: a peer that declines to
     * identify itself is refused at the TLS layer, before any application
     * byte is read.
     */
    fun harden(socket: SSLSocket, asServer: Boolean = false) {
        socket.enabledProtocols = arrayOf("TLSv1.3")
        if (asServer) {
            socket.useClientMode = false
            socket.needClientAuth = true
        }
    }

    companion object {
        private const val ALIAS = "maze-connect-identity"

        /** Guards against a mis-set configuration turning into a downgrade. */
        fun negotiatedTls13(socket: SSLSocket): Boolean =
            socket.session?.protocol == "TLSv1.3"

        fun emptyKeyStore(): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            .apply { load(null) }
    }
}
