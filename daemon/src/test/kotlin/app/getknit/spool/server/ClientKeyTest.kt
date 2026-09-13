// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.CountingResolverProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the per-client limits are keyed under: the address literal, an IPv6 client folded to its /64. */
class ClientKeyTest {
    @Test
    fun anIpv4AddressIsItsOwnKey() {
        assertEquals("203.0.113.9", clientKey("203.0.113.9"))
        assertEquals("127.0.0.1", clientKey("127.0.0.1"))
    }

    @Test
    fun anIpv6ClientIsKeyedByItsSlash64WhateverTheSpelling() {
        val key = "2001:db8:0:1::/64"
        assertEquals(key, clientKey("2001:db8:0:1::a"))
        assertEquals(key, clientKey("2001:db8:0:1:ffff::b"))
        // The uncompressed form Inet6Address.getHostAddress() emits for a direct peer.
        assertEquals(key, clientKey("2001:db8:0:1:0:0:0:1"))
        assertEquals(key, clientKey("2001:DB8:0:1::A"))
        assertEquals(key, clientKey("2001:db8:0:1:0:0:0:1%eth0"))
        assertEquals(key, clientKey("2001:db8:0:1::a%3"))
    }

    @Test
    fun aDifferentSlash64IsADifferentKey() {
        assertEquals("2001:db8:0:2::/64", clientKey("2001:db8:0:2::a"))
        assertEquals("fe80:0:0:0::/64", clientKey("fe80::1%eth0"))
        assertEquals("0:0:0:0::/64", clientKey("::1"))
    }

    @Test
    fun anIpv4MappedAddressIsTheIpv4ClientItWraps() {
        assertEquals("203.0.113.9", clientKey("::ffff:203.0.113.9"))
        assertEquals("203.0.113.9", clientKey("::ffff:cb00:7109"))
    }

    /** Never a throw and never a lookup: what cannot be parsed is keyed as it came. */
    @Test
    fun anythingElseIsKeyedAsWrittenWithoutResolving() {
        CountingResolverProvider.prime()
        val forwardBefore = CountingResolverProvider.forward.get()
        val reverseBefore = CountingResolverProvider.reverse.get()
        for (odd in listOf("unknown", "spool.example", "1.2.3.4:5678", "zz::1", "1:2:3", "", "%eth0", "::g")) {
            assertEquals(odd, clientKey(odd), odd)
        }
        assertEquals(forwardBefore, CountingResolverProvider.forward.get(), "forward lookups")
        assertEquals(reverseBefore, CountingResolverProvider.reverse.get(), "reverse lookups")
    }
}
