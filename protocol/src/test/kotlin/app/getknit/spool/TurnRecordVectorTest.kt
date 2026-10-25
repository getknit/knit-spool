// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.Limits
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.TurnCred
import app.getknit.spool.protocol.TurnReq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TURN credentials at the record layer (knit-next `docs/SPOOL_PROTOCOL.md` §7.6). The §13 vectors
 * are pinned by [SpecVectorTest]; this checks what a HELLO without TURN omits, that the `turncred`
 * vector round-trips, and the two vectors this repo produces for several TURN servers (S-7.6-48/49,
 * ADR 2026-10.u5f4).
 *
 * [HELLO_SPOOL_TURN_HOSTS] and [TURNREQ_HOST] were made independently of kotlinx, by a hand-rolled
 * CBOR encoder in Python from the contract's inputs: `helloSpoolTurn`'s map with `turnHosts =
 * ["spool.example:3478", "[2001:db8::7]:3478"]` as its seventh key, and `{t: "turnreq", q: 7, host:
 * "[2001:db8::7]:3478"}` in that key order. The same script reproduced §13's `helloSpoolTurn` and
 * `turnreq` byte for byte first.
 */
class TurnRecordVectorTest {
    @Test
    fun aHelloWithoutTurnOmitsTheField() {
        val hello = RecordCodec.encode(Hello(t = RecordType.HELLO, v = RECORD_VERSION))
        assertEquals("a261746568656c6c6f617601", hello.toHexString())
        assertNull(RecordCodec.decode<Hello>(hello)?.turn)
    }

    @Test
    fun turnCredRoundTrips() {
        val decoded = RecordCodec.decode<TurnCred>(SpecVectorTest.EXPECTED.getValue("turncred").hexToByteArray())
        assertEquals(7L, decoded?.q)
        assertEquals(2, decoded?.urls?.size)
        assertEquals(1_800_000_000L, decoded?.exp)
    }

    /** `turnHosts` encodes last, after every older key, so the map is `helloSpoolTurn`'s plus one entry. */
    @Test
    fun helloSpoolTurnHostsMatchesTheEncoder() {
        val encoded =
            RecordCodec.encode(
                Hello(
                    t = RecordType.HELLO,
                    v = RECORD_VERSION,
                    min = 1,
                    limits =
                        Limits(
                            maxBlob = 65_536,
                            maxRecord = 131_072,
                            maxScopes = 64,
                            maxPull = 64,
                            maxFramesCap = 1_000,
                            maxTtlMs = 604_800_000L,
                        ),
                    powBits = 20,
                    turn = true,
                    turnHosts = listOf("spool.example:3478", "[2001:db8::7]:3478"),
                ),
            )
        assertEquals(HELLO_SPOOL_TURN_HOSTS, encoded.toHexString(), "helloSpoolTurnHosts diverges")
        // A map of 7 whose first six entries are helloSpoolTurn's, byte for byte.
        val turn = SpecVectorTest.EXPECTED.getValue("helloSpoolTurn")
        assertEquals("a7" + turn.drop(2), HELLO_SPOOL_TURN_HOSTS.take(turn.length))
    }

    @Test
    fun turnreqHostMatchesTheEncoder() {
        val encoded = RecordCodec.encode(TurnReq(t = RecordType.TURNREQ, q = 7L, host = "[2001:db8::7]:3478"))
        assertEquals(TURNREQ_HOST, encoded.toHexString(), "turnreqHost diverges")
    }

    /** What a spool reads from each: the list in order, and the host a client named. */
    @Test
    fun theHostVectorsDecode() {
        val hello = RecordCodec.decode<Hello>(HELLO_SPOOL_TURN_HOSTS.hexToByteArray())
        assertEquals(true, hello?.turn)
        assertEquals(listOf("spool.example:3478", "[2001:db8::7]:3478"), hello?.turnHosts)
        val req = RecordCodec.decode<TurnReq>(TURNREQ_HOST.hexToByteArray())
        assertEquals(7L, req?.q)
        assertEquals("[2001:db8::7]:3478", req?.host)
    }

    /**
     * Additive both ways: §13's `turnreq` decodes with no `host`, and a spool that predates `host`
     * reads `turnreqHost` as the same request, so an old spool answers with its only server.
     */
    @Test
    fun hostIsOptionalAndIgnorableAndATextStringOnly() {
        assertNull(RecordCodec.decode<TurnReq>(SpecVectorTest.EXPECTED.getValue("turnreq").hexToByteArray())?.host)
        assertNull(RecordCodec.decode<Hello>(SpecVectorTest.EXPECTED.getValue("helloSpoolTurn").hexToByteArray())?.turnHosts)
        assertEquals(7L, RecordCodec.decode<OldTurnReq>(TURNREQ_HOST.hexToByteArray())?.q)
        // `{t: "turnreq", q: 7, host: 7}`: a host that is not a text string does not decode (malformed).
        assertNull(RecordCodec.decode<TurnReq>("a36174677475726e72657161710764686f737407".hexToByteArray()))
    }

    /** `turnreq` as a spool from before S-7.6-49 declares it. */
    @kotlinx.serialization.Serializable
    private class OldTurnReq(
        val t: String,
        val q: Long,
    )

    internal companion object {
        const val HELLO_SPOOL_TURN_HOSTS =
            "a761746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                "0c840067706f774269747314647475726ef5697475726e486f737473827273706f6f" +
                "6c2e6578616d706c653a33343738725b323030313a6462383a3a375d3a33343738"

        const val TURNREQ_HOST =
            "a36174677475726e72657161710764686f7374725b323030313a6462383a3a375d3a" +
                "33343738"
    }
}
