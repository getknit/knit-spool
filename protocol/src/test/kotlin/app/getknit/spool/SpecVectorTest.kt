// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Blob
import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Event
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.Limits
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Pow
import app.getknit.spool.protocol.PowStamp
import app.getknit.spool.protocol.Pull
import app.getknit.spool.protocol.Push
import app.getknit.spool.protocol.RECORD_VERSION
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conformance against SPOOL_PROTOCOL.md §13 (the Knit repo's spec): every record vector, the
 * digest vectors, and the PoW vector, byte-exact. These constants are the spec's appendix verbatim
 * — if this file and the spec ever disagree, the spec wins and this implementation is wrong.
 */
class SpecVectorTest {
    private fun fixture(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test
    fun recordVectorsMatchTheSpec() {
        val vectors =
            mapOf(
                "helloSpool" to
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
                        ),
                    ),
                "helloClient" to RecordCodec.encode(Hello(t = RecordType.HELLO, v = RECORD_VERSION)),
                "sub" to
                    RecordCodec.encode(
                        Sub(
                            t = RecordType.SUB,
                            q = 1L,
                            subs =
                                listOf(
                                    ScopeSub(
                                        scope = fixture(32, 1),
                                        bounds = ScopeBounds(maxFrames = 400, ttlMs = 172_800_000L, maxBlob = 65_536),
                                        pow = PowStamp(n = 42L, d = 20_680L),
                                    ),
                                ),
                        ),
                    ),
                "digest" to
                    RecordCodec.encode(
                        Digest(
                            t = RecordType.DIGEST,
                            scope = fixture(32, 1),
                            digest = fixture(8, 2),
                            count = 3,
                            full = false,
                            bounds = ScopeBounds(maxFrames = 400, ttlMs = 172_800_000L, maxBlob = 65_536),
                        ),
                    ),
                "listRequest" to RecordCodec.encode(ScopeList(t = RecordType.LIST, q = 2L, scope = fixture(32, 1))),
                "listResponse" to
                    RecordCodec.encode(
                        ScopeList(
                            t = RecordType.LIST,
                            q = 2L,
                            scope = fixture(32, 1),
                            blobIds = listOf(fixture(32, 3), fixture(32, 4)),
                            tombstones = listOf(fixture(32, 5)),
                        ),
                    ),
                "pull" to
                    RecordCodec.encode(
                        Pull(t = RecordType.PULL, q = 3L, scope = fixture(32, 1), blobIds = listOf(fixture(32, 3))),
                    ),
                "blob" to
                    RecordCodec.encode(
                        Blob(t = RecordType.BLOB, scope = fixture(32, 1), blobId = fixture(32, 3), data = fixture(48, 6)),
                    ),
                "push" to
                    RecordCodec.encode(
                        Push(
                            t = RecordType.PUSH,
                            q = 4L,
                            scope = fixture(32, 1),
                            blobId = fixture(32, 3),
                            data = fixture(48, 6),
                            pow = PowStamp(n = 42L, d = 20_680L),
                        ),
                    ),
                "event" to
                    RecordCodec.encode(
                        Event(t = RecordType.EVENT, scope = fixture(32, 1), blobId = fixture(32, 3), data = fixture(48, 6)),
                    ),
                "okBare" to RecordCodec.encode(Ok(t = RecordType.OK, q = 3L)),
                "okMissing" to RecordCodec.encode(Ok(t = RecordType.OK, q = 3L, missing = listOf(fixture(32, 4)))),
                "errScoped" to
                    RecordCodec.encode(
                        Err(t = RecordType.ERR, code = ErrCode.TOMBSTONED, q = 4L, scope = fixture(32, 1)),
                    ),
                "errRate" to
                    RecordCodec.encode(
                        Err(t = RecordType.ERR, code = ErrCode.RATE, msg = "slow down", retryMs = 30_000L),
                    ),
            )
        for ((name, encoded) in vectors) {
            assertEquals(EXPECTED.getValue(name), encoded.toHex(), "record vector '$name' diverges from the spec")
        }
    }

    @Test
    fun recordsDecodeAndTolerateTheUnknown() {
        val push =
            RecordCodec.encode(
                Push(t = RecordType.PUSH, q = 4L, scope = fixture(32, 1), blobId = fixture(32, 3), data = fixture(48, 6)),
            )
        assertEquals(RecordType.PUSH, RecordCodec.peekType(push))
        assertEquals(4L, RecordCodec.decode<Push>(push)?.q)
        assertNull(RecordCodec.decode<Push>(byteArrayOf(0x42, 0x00)))
        assertNull(RecordCodec.peekType(ByteArray(0)))
    }

    @Test
    fun digestVectorsMatchTheSpec() {
        assertEquals(0L, ScopeDigest.fold(emptyList()))
        assertEquals("0000000000000000", ScopeDigest.toBytes(0L).toHex())
        assertEquals(
            "834b13d8dc060ce5",
            ScopeDigest.toBytes(ScopeDigest.fold(listOf(fixture(32, 11), fixture(32, 12), fixture(32, 13)))).toHex(),
        )
    }

    @Test
    fun powVectorMatchesTheSpec() {
        val scopeId = fixture(32, 9)
        assertEquals(
            "00b776b91276563998bb57f8f3f73a05e0d8afcd3dce8a2583d6d466aadb620e",
            Pow.digest(scopeId, day = 20_680L, n = 8L).toHex(),
        )
        assertTrue(Pow.verify(scopeId, day = 20_680L, n = 8L, bits = 8))
        for (earlier in 0L until 8L) {
            assertFalse(Pow.verify(scopeId, day = 20_680L, n = earlier, bits = 8), "n=$earlier should not satisfy 8 bits")
        }
    }

    @Test
    fun theMinerFindsTheSpecsSmallestStamp() {
        val scopeId = fixture(32, 9)
        assertEquals(8L, Pow.stamp(scopeId, day = 20_680L, bits = 8))
        assertEquals(null, Pow.stamp(scopeId, day = 20_680L, bits = 8, maxAttempts = 8L))
        assertEquals(0L, Pow.stamp(scopeId, day = 20_680L, bits = 0))
    }

    private companion object {
        val EXPECTED =
            mapOf(
                "helloSpool" to
                    "a561746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c840067706f774269747314",
                "helloClient" to "a261746568656c6c6f617601",
                "sub" to
                    "a3617463737562617101647375627381a36573636f7065582001080f161d242b3239" +
                    "40474e555c636a71787f868d949ba2a9b0b7bec5ccd3da66626f756e6473a3696d61" +
                    "784672616d65731901906574746c4d731a0a4cb800676d6178426c6f621a00010000" +
                    "63706f77a2616e182a61641950c8",
                "digest" to
                    "a66174666469676573746573636f7065582001080f161d242b323940474e555c636a" +
                    "71787f868d949ba2a9b0b7bec5ccd3da6664696765737448020910171e252c336563" +
                    "6f756e74036466756c6cf466626f756e6473a3696d61784672616d65731901906574" +
                    "746c4d731a0a4cb800676d6178426c6f621a00010000",
                "listRequest" to
                    "a36174646c6973746171026573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da",
                "listResponse" to
                    "a56174646c6973746171026573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da67626c6f62496473825820030a11181f26" +
                    "2d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc5820040b12192027" +
                    "2e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dd6a746f6d6273746f" +
                    "6e6573815820050c131a21282f363d444b525960676e757c838a91989fa6adb4bbc2" +
                    "c9d0d7de",
                "pull" to
                    "a461746470756c6c6171036573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da67626c6f62496473815820030a11181f26" +
                    "2d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc",
                "blob" to
                    "a4617464626c6f626573636f7065582001080f161d242b323940474e555c636a7178" +
                    "7f868d949ba2a9b0b7bec5ccd3da66626c6f6249645820030a11181f262d343b4249" +
                    "50575e656c737a81888f969da4abb2b9c0c7ced5dc64646174615830060d141b2229" +
                    "30373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb02091017" +
                    "1e252c333a41484f",
                "push" to
                    "a6617464707573686171046573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da66626c6f6249645820030a11181f262d34" +
                    "3b424950575e656c737a81888f969da4abb2b9c0c7ced5dc64646174615830060d14" +
                    "1b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb02" +
                    "0910171e252c333a41484f63706f77a2616e182a61641950c8",
                "event" to
                    "a46174656576656e746573636f7065582001080f161d242b323940474e555c636a71" +
                    "787f868d949ba2a9b0b7bec5ccd3da66626c6f6249645820030a11181f262d343b42" +
                    "4950575e656c737a81888f969da4abb2b9c0c7ced5dc64646174615830060d141b22" +
                    "2930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910" +
                    "171e252c333a41484f",
                "okBare" to "a26174626f6b617103",
                "okMissing" to
                    "a36174626f6b617103676d697373696e67815820040b121920272e353c434a51585f" +
                    "666d747b828990979ea5acb3bac1c8cfd6dd",
                "errScoped" to
                    "a461746365727264636f64656a746f6d6273746f6e65646171046573636f70655820" +
                    "01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3da",
                "errRate" to "a461746365727264636f64656472617465636d736769736c6f7720646f776e6772657472794d73197530",
            )
    }
}
