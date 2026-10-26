// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * A self-signed certificate for 127.0.0.1, minted once per test JVM, and the two contexts that use
 * it: [server] for a [WsTestServer] that speaks `wss://`, [client] for a runner that trusts it.
 * Every CI path was plain `ws://` until this, so the runner's TLS client never ran there (#6).
 */
internal object TestTls {
    private const val ALIAS = "spool"
    private const val PASSWORD = "conformance"
    private const val KEY_BITS = 2048

    private val keyStore =
        buildKeyStore {
            certificate(ALIAS) {
                password = PASSWORD
                hash = HashAlgorithm.SHA256
                keySizeInBits = KEY_BITS
            }
        }

    val server: SSLContext by lazy {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(keyStore, PASSWORD.toCharArray())
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
    }

    val client: SSLContext by lazy {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(keyStore)
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }
}
