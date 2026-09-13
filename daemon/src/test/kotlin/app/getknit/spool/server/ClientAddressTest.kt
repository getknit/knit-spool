// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.CountingResolverProvider
import app.getknit.spool.protocol.CloseCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which address a connection is accounted under, and that finding it costs no name lookup.
 * The per-IP limits key on [clientKey] of `origin.remoteAddress`: the socket peer, or with
 * `trustProxy` the hop the proxy appended to `X-Forwarded-For`.
 */
class ClientAddressTest {
    /**
     * `origin.remoteHost` was `InetSocketAddress.hostName`: a reverse lookup and forward
     * confirmation on the accepting worker for every connection. Loopback's PTR is answered by
     * `/etc/hosts`, but the JDK's resolver hook fires first, so the turnstile still counts it.
     */
    @Test
    fun acceptingAConnectionResolvesNoName() {
        CountingResolverProvider.prime()
        val reverseBefore = CountingResolverProvider.reverse.get()
        val forwardBefore = CountingResolverProvider.forward.get()
        withServer {
            repeat(2) {
                connect { helloHandshake() }
            }
        }
        assertEquals(reverseBefore, CountingResolverProvider.reverse.get(), "reverse lookups")
        assertEquals(forwardBefore, CountingResolverProvider.forward.get(), "forward lookups")
    }

    @Test
    fun aTrustedProxyHopIsTheClientAddress() {
        withServer(testConfig(trustProxy = true, maxConnsPerIp = 1)) {
            connect(forwardedFor = "203.0.113.9") {
                helloHandshake()
                connect(forwardedFor = "203.0.113.9") { awaitClose(CloseCode.ABUSE) }
                connect(forwardedFor = "203.0.113.10") { helloHandshake() }
            }
        }
    }

    @Test
    fun anIpv6ClientBehindATrustedProxyIsKeyedByItsSlash64() {
        withServer(testConfig(trustProxy = true, maxConnsPerIp = 1)) {
            connect(forwardedFor = "2001:db8:0:1::a") {
                helloHandshake()
                connect(forwardedFor = "2001:db8:0:1:ffff::b") { awaitClose(CloseCode.ABUSE) }
                connect(forwardedFor = "2001:db8:0:2::a") { helloHandshake() }
                connect(forwardedFor = "203.0.113.9") { helloHandshake() }
            }
        }
    }

    /** Without `trustProxy` the header is a client's claim, and both connections are loopback. */
    @Test
    fun aForgedForwardedForIsIgnoredWithoutTrustProxy() {
        withServer(testConfig(maxConnsPerIp = 1)) {
            connect(forwardedFor = "2001:db8::1") {
                helloHandshake()
                connect(forwardedFor = "2001:db8:ffff::1") { awaitClose(CloseCode.ABUSE) }
            }
        }
    }
}
