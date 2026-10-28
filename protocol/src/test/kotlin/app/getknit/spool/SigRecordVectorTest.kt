// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.Limits
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.Sig
import app.getknit.spool.protocol.SigInfo
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signals at the record layer (knit-next `docs/SPOOL_PROTOCOL.md` §7.8; ADR 2026-10.2q49). This repo
 * produces §13's two sig record vectors, and this pins them.
 *
 * [HELLO_SPOOL_SIG] and [SIG_RECORD] were made independently of kotlinx, by a hand-rolled CBOR encoder
 * in Python from the contract's inputs: `helloSpool`'s map with `sig = {maxBytes: 8192, rate: 2}` as
 * its sixth key, and `{t: "sig", scope, data}` in that key order, with `cast`'s scope and data. The
 * same script reproduced §13's `helloSpool` and `cast` byte for byte first. Neither is named `sig`,
 * which §13 already gives the ticket signature.
 */
class SigRecordVectorTest {
    @Test
    fun aHelloWithoutSigOmitsTheField() {
        val hello = RecordCodec.encode(Hello(t = RecordType.HELLO, v = RECORD_VERSION))
        assertEquals("a261746568656c6c6f617601", hello.toHexString())
        assertNull(RecordCodec.decode<Hello>(hello)?.sig)
    }

    /** `sig` encodes last, after every older key, so the map is `helloSpool`'s plus one entry. */
    @Test
    fun helloSpoolSigMatchesTheEncoder() {
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
                    sig = SigInfo(maxBytes = 8_192, rate = 2),
                ),
            )
        assertEquals(HELLO_SPOOL_SIG, encoded.toHexString(), "helloSpoolSig diverges")
        val spool = SpecVectorTest.EXPECTED.getValue("helloSpool")
        assertEquals("a6" + spool.drop(2), HELLO_SPOOL_SIG.take(spool.length))
    }

    /** Declared last in [Hello], so it encodes after `turnHosts` too, wherever both are present. */
    @Test
    fun sigEncodesAfterTurnHosts() {
        val encoded =
            RecordCodec
                .encode(
                    Hello(t = RecordType.HELLO, v = RECORD_VERSION, turnHosts = listOf("spool.example:3478"), sig = SigInfo(1, 1)),
                ).toHexString()
        assertTrue(encoded.indexOf("697475726e486f737473") < encoded.indexOf("63736967"), encoded)
    }

    @Test
    fun sigRecordMatchesTheEncoder() {
        val encoded = RecordCodec.encode(Sig(t = RecordType.SIG, scope = SCOPE, data = DATA))
        assertEquals(SIG_RECORD, encoded.toHexString(), "sigRecord diverges")
        // `cast`'s map, byte for byte, but for the record type.
        val cast = SpecVectorTest.EXPECTED.getValue("cast")
        assertEquals(cast.substringAfter("6463617374"), SIG_RECORD.substringAfter("63736967"))
    }

    /** What each side reads from each: the bounds a client keeps to, and the scope and data a spool relays. */
    @Test
    fun theVectorsDecode() {
        val sig = RecordCodec.decode<Hello>(HELLO_SPOOL_SIG.hexToByteArray())?.sig
        assertEquals(8_192, sig?.maxBytes)
        assertEquals(2, sig?.rate)
        val record = RecordCodec.decode<Sig>(SIG_RECORD.hexToByteArray())
        assertContentEquals(SCOPE, record?.scope)
        assertContentEquals(DATA, record?.data)
        assertEquals(RecordType.SIG, RecordCodec.peekType(SIG_RECORD.hexToByteArray()))
    }

    /** Additive (B-2-2): a client or spool that predates `sig` reads `helloSpoolSig` as `helloSpool`. */
    @Test
    fun sigIsIgnorableByAnOlderReader() {
        val old = RecordCodec.decode<OldHello>(HELLO_SPOOL_SIG.hexToByteArray())
        assertEquals(20, old?.powBits)
        assertEquals(RecordType.HELLO, old?.t)
    }

    /** `hello` as a reader from before §7.8 declares it, down to `powBits`. */
    @Serializable
    private class OldHello(
        val t: String,
        val v: Int,
        val min: Int? = null,
        val powBits: Int? = null,
    )

    internal companion object {
        val SCOPE = ByteArray(32) { it.toByte() }
        val DATA = ByteArray(16) { (0xa0 + it).toByte() }

        const val HELLO_SPOOL_SIG =
            "a661746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                "0c840067706f77426974731463736967a2686d617842797465731920006472617465" +
                "02"

        const val SIG_RECORD =
            "a36174637369676573636f70655820000102030405060708090a0b0c0d0e0f101112" +
                "131415161718191a1b1c1d1e1f646461746150a0a1a2a3a4a5a6a7a8a9aaabacadae" +
                "af"
    }
}
