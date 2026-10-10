// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Cast
import app.getknit.spool.protocol.CastInfo
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **Experimental.** Vectors for casts, from the draft in knit-next's `docs/VOICE_CALLS.md` §2.7
 * (proposed spec §7.7). Every hex string was produced independently, by a hand-rolled CBOR encoder
 * that reproduces [TicketVectorTest]'s `helloEntStun` and pyca/cryptography's Ed25519, so neither
 * kotlinx nor the JDK checks itself. They stay out of [SpecVectorTest] until the spec accepts §7.7.
 */
class CastRecordVectorTest {
    @Test
    fun castRecordVectorsMatchTheDraft() {
        val hello = Hello(t = RecordType.HELLO, v = RECORD_VERSION, ent = true, cast = CastInfo(maxBytes = 2_048, rate = 4))
        assertEquals(EXPECTED.getValue("helloCast"), RecordCodec.encode(hello).toHexString())
        // One record for both directions: what a client sends is exactly what the spool relays.
        assertEquals(EXPECTED.getValue("cast"), RecordCodec.encode(Cast(t = RecordType.CAST, scope = SCOPE, data = DATA)).toHexString())
    }

    @Test
    fun aCastTicketCarriesBitThreeAndSignsLikeAnyOther() {
        val ticket =
            TicketVectorTest.fixture().let {
                Ticket.forKey(
                    TicketVectorTest.SIGNING_KEY,
                    it.aud,
                    it.nbf,
                    it.exp,
                    it.ps,
                    Tickets.FL_TURN or Tickets.FL_CAST,
                )
            }
        val (tk, sig) = Tickets.issue(TicketVectorTest.SIGNING_KEY, ticket)
        assertEquals(EXPECTED.getValue("tkCast"), tk.toHexString(), "ticket body diverges from the draft")
        assertEquals(EXPECTED.getValue("sigCast"), sig.toHexString(), "ticket signature diverges from the draft")
    }

    @Test
    fun aHelloWithoutCastOmitsTheField() {
        val hello = RecordCodec.encode(Hello(t = RecordType.HELLO, v = RECORD_VERSION))
        assertEquals("a261746568656c6c6f617601", hello.toHexString())
        assertNull(RecordCodec.decode<Hello>(hello)?.cast)
    }

    @Test
    fun aCastRoundTripsAndCarriesNoQ() {
        val decoded = RecordCodec.decode<Cast>(EXPECTED.getValue("cast").hexToByteArray())
        assertContentEquals(SCOPE, decoded?.scope)
        assertContentEquals(DATA, decoded?.data)
        assertEquals(RecordType.CAST, RecordCodec.peekType(EXPECTED.getValue("cast").hexToByteArray()))
    }

    private companion object {
        val SCOPE = ByteArray(32) { it.toByte() }
        val DATA = ByteArray(16) { (0xa0 + it).toByte() }

        val EXPECTED =
            mapOf(
                "helloCast" to "a461746568656c6c6f61760163656e74f56463617374a2686d61784279746573190800647261746504",
                "cast" to
                    "a3617464636173746573636f70655820000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                    "646461746150a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
                "tkCast" to
                    "a7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c65636e62661a6b49d200636578701a6b4b2380" +
                    "62707350000102030405060708090a0b0c0d0e0f62666c09",
                "sigCast" to
                    "9f0d82c5e15ddc486fc693a848dd2e937df32e505f22631dde6a49f82435d147" +
                    "b64da30b5ac432ef18ee9e2349c4c8915cc8e9d5a3e829f95c2784bc5a5f530f",
            )
    }
}
