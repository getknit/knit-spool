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
 * **Experimental.** Vectors for TURN provisioning, which the spec does not carry yet: they come from
 * the draft in knit-next's `docs/VOICE_CALLS.md` §2 (proposed spec §7.6), and were cross-checked
 * against an independent CBOR encoder that also reproduces `SpecVectorTest`'s `helloSpool` byte for
 * byte. They stay out of [SpecVectorTest] — whose constants are the spec's appendix verbatim — and
 * move into its `EXPECTED` map once the spec accepts §7.6.
 */
class TurnRecordVectorTest {
    @Test
    fun turnRecordVectorsMatchTheDraft() {
        val vectors =
            mapOf(
                "helloSpoolTurn" to
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
                        ),
                    ),
                "turnreq" to RecordCodec.encode(TurnReq(t = RecordType.TURNREQ, q = 7L)),
                "turncred" to
                    RecordCodec.encode(
                        TurnCred(
                            t = RecordType.TURNCRED,
                            q = 7L,
                            urls = listOf("turn:spool.example:3478?transport=udp", "turns:spool.example:5349?transport=tcp"),
                            user = "1800000000:bmjbsibhfy2tyq2k",
                            pass = "yV18C+LcL6biIfL7ZVOuV9xVuoU=",
                            exp = 1_800_000_000L,
                        ),
                    ),
            )
        for ((name, encoded) in vectors) {
            assertEquals(EXPECTED.getValue(name), encoded.toHexString(), "record vector '$name' diverges from the draft")
        }
    }

    @Test
    fun aHelloWithoutTurnOmitsTheField() {
        val hello = RecordCodec.encode(Hello(t = RecordType.HELLO, v = RECORD_VERSION))
        assertEquals("a261746568656c6c6f617601", hello.toHexString())
        assertNull(RecordCodec.decode<Hello>(hello)?.turn)
    }

    @Test
    fun turnCredRoundTrips() {
        val decoded = RecordCodec.decode<TurnCred>(EXPECTED.getValue("turncred").hexToByteArray())
        assertEquals(7L, decoded?.q)
        assertEquals(2, decoded?.urls?.size)
        assertEquals(1_800_000_000L, decoded?.exp)
    }

    private companion object {
        val EXPECTED =
            mapOf(
                "helloSpoolTurn" to
                    "a661746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c840067706f774269747314647475726ef5",
                "turnreq" to "a26174677475726e726571617107",
                "turncred" to
                    "a66174687475726e637265646171076475726c738278257475726e3a73706f6f6c2e" +
                    "6578616d706c653a333437383f7472616e73706f72743d75647078267475726e733a" +
                    "73706f6f6c2e6578616d706c653a353334393f7472616e73706f72743d7463706475" +
                    "736572781b313830303030303030303a626d6a6273696268667932747971326b6470" +
                    "617373781c79563138432b4c634c36626949664c375a564f7556397856756f553d63" +
                    "6578701a6b49d200",
            )
    }
}
