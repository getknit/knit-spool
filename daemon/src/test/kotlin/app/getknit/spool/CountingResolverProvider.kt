// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import java.net.InetAddress
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolver.LookupPolicy
import java.net.spi.InetAddressResolverProvider
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream
import kotlin.test.assertTrue

/**
 * The JVM's name resolver for the test suite: the built-in one, with a turnstile in front. Every
 * forward (`lookupByName`) and reverse (`lookupByAddress`) lookup the daemon or a test provokes is
 * counted before it is delegated, so a test can assert that a code path resolves nothing — which
 * is the property the daemon claims about client addresses and cannot show any other way.
 *
 * Registered through `META-INF/services/java.net.spi.InetAddressResolverProvider`; the JDK loads
 * it once, on the first lookup in the process. Delegating, so no test's behaviour changes.
 */
class CountingResolverProvider : InetAddressResolverProvider() {
    override fun name(): String = "counting"

    override fun get(configuration: Configuration): InetAddressResolver {
        val builtin = configuration.builtinResolver()
        loaded = true
        return object : InetAddressResolver {
            override fun lookupByName(
                host: String,
                lookupPolicy: LookupPolicy,
            ): Stream<InetAddress> {
                forward.incrementAndGet()
                return builtin.lookupByName(host, lookupPolicy)
            }

            override fun lookupByAddress(addr: ByteArray): String {
                reverse.incrementAndGet()
                return builtin.lookupByAddress(addr)
            }
        }
    }

    companion object {
        val forward = AtomicInteger(0)
        val reverse = AtomicInteger(0)

        @Volatile
        var loaded = false

        /**
         * Makes sure the JDK has loaded this provider, so a zero on a counter means "no lookup"
         * and not "no resolver yet". The first lookup in the process triggers the load; a name
         * that `/etc/hosts` answers keeps it off the network.
         */
        fun prime() {
            InetAddress.getByName("localhost")
            assertTrue(loaded, "CountingResolverProvider was not picked up from META-INF/services")
        }
    }
}
