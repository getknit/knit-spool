// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Achunk
import app.getknit.spool.protocol.Aget
import app.getknit.spool.protocol.Ahas
import app.getknit.spool.protocol.Ahave
import app.getknit.spool.protocol.Aput
import app.getknit.spool.protocol.Blob
import app.getknit.spool.protocol.Cast
import app.getknit.spool.protocol.CastInfo
import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.CommonsInfo
import app.getknit.spool.protocol.Digest
import app.getknit.spool.protocol.Ent
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
import app.getknit.spool.protocol.RoomInfo
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.ScopeDigest
import app.getknit.spool.protocol.ScopeList
import app.getknit.spool.protocol.ScopeSub
import app.getknit.spool.protocol.Sub
import app.getknit.spool.protocol.Ticket
import app.getknit.spool.protocol.Tickets
import app.getknit.spool.protocol.TurnCred
import app.getknit.spool.protocol.TurnReq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conformance against SPOOL_PROTOCOL.md §13 (the Knit repo's spec): every record vector, the
 * digest vectors, the PoW vector, the §7.4 commons derivations and the §7.6 ticket vectors,
 * byte-exact. These constants are the spec's appendix verbatim — if this file and the spec ever
 * disagree, the spec wins and this implementation is wrong.
 */
class SpecVectorTest {
    private fun fixture(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    private fun ByteArray.toHex() = toHexString()

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
                "helloSpoolAttach" to
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
                                    maxAttachBytes = 16_777_216,
                                    maxAChunk = 49_221,
                                    maxAget = 32,
                                ),
                            powBits = 20,
                        ),
                    ),
                // §7.4: a spool with a commons advertises its name and pinned bounds — never its id —
                // and `attach` only when true, so a room without attachments is the shorter map.
                "helloSpoolCommons" to
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
                            commons = CommonsInfo(name = "Home", maxFrames = 500, ttlMs = 86_400_000L, maxBlob = 65_536),
                        ),
                    ),
                "helloSpoolCommonsAttach" to
                    RecordCodec.encode(
                        Hello(
                            t = RecordType.HELLO,
                            v = RECORD_VERSION,
                            commons = CommonsInfo(maxFrames = 500, ttlMs = 86_400_000L, maxBlob = 65_536, attach = true),
                        ),
                    ),
                // §7.4 rooms: two rooms, the first repeated as `commons` without its tag, each `rooms`
                // entry led by its one-way tag. Copied from knit-next 9451527 (docs/SPOOL_PROTOCOL.md §13).
                "helloSpoolRooms" to
                    RecordCodec.encode(
                        Hello(
                            t = RecordType.HELLO,
                            v = RECORD_VERSION,
                            commons = CommonsInfo(name = "Home", maxFrames = 500, ttlMs = 86_400_000L, maxBlob = 65_536),
                            rooms =
                                listOf(
                                    RoomInfo(
                                        tag = Commons.tag(Commons.scopeId(fixture(32, 10))),
                                        name = "Home",
                                        maxFrames = 500,
                                        ttlMs = 86_400_000L,
                                        maxBlob = 65_536,
                                    ),
                                    RoomInfo(
                                        tag = Commons.tag(Commons.scopeId(fixture(32, 11))),
                                        maxFrames = 200,
                                        ttlMs = 86_400_000L,
                                        maxBlob = 65_536,
                                        attach = true,
                                    ),
                                ),
                        ),
                    ),
                // §7.5: the send-side moderation request, present only when the operator set it.
                "helloSpoolModeration" to
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
                            moderation = true,
                        ),
                    ),
                "ahave" to
                    RecordCodec.encode(Ahave(t = RecordType.AHAVE, q = 5L, scope = fixture(32, 1), aid = fixture(32, 7))),
                "ahas" to
                    RecordCodec.encode(
                        Ahas(
                            t = RecordType.AHAS,
                            q = 5L,
                            scope = fixture(32, 1),
                            aid = fixture(32, 7),
                            total = 3,
                            bits = fixture(1, 9),
                        ),
                    ),
                "ahasDead" to
                    RecordCodec.encode(
                        Ahas(
                            t = RecordType.AHAS,
                            q = 5L,
                            scope = fixture(32, 1),
                            aid = fixture(32, 7),
                            total = 0,
                            bits = ByteArray(0),
                            dead = true,
                        ),
                    ),
                "aget" to
                    RecordCodec.encode(
                        Aget(t = RecordType.AGET, q = 6L, scope = fixture(32, 1), aid = fixture(32, 7), from = 0, n = 2),
                    ),
                "achunk" to
                    RecordCodec.encode(
                        Achunk(
                            t = RecordType.ACHUNK,
                            scope = fixture(32, 1),
                            aid = fixture(32, 7),
                            idx = 1,
                            total = 3,
                            cid = fixture(32, 8),
                            data = fixture(48, 6),
                        ),
                    ),
                "aput" to
                    RecordCodec.encode(
                        Aput(
                            t = RecordType.APUT,
                            q = 7L,
                            scope = fixture(32, 1),
                            aid = fixture(32, 7),
                            idx = 1,
                            total = 3,
                            cid = fixture(32, 8),
                            data = fixture(48, 6),
                            pow = PowStamp(n = 42L, d = 20_680L),
                        ),
                    ),
                // §7.6: TURN credentials, then a ticket-checking spool's HELLO with its STUN list and gates.
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
                // §7.6 several TURN servers (S-7.6-48/49): helloSpoolTurn's map with `turnHosts` as its
                // seventh key, and a turnreq naming the second entry. This repo produced them (ADR
                // 2026-10.u5f4); the spec carries them since knit-next 78d5fe633 (docs/SPOOL_PROTOCOL.md §13).
                "helloSpoolTurnHosts" to
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
                    ),
                "turnreqHost" to RecordCodec.encode(TurnReq(t = RecordType.TURNREQ, q = 7L, host = "[2001:db8::7]:3478")),
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
                "helloEntStun" to
                    RecordCodec.encode(Hello(t = RecordType.HELLO, v = RECORD_VERSION, ent = true, stun = listOf("stun:lab.example:3478"))),
                "helloEntGates" to
                    RecordCodec.encode(
                        Hello(
                            t = RecordType.HELLO,
                            v = RECORD_VERSION,
                            ent = true,
                            entGates = Tickets.FL_TURN or Tickets.FL_FILES or Tickets.FL_CAST,
                        ),
                    ),
                // §7.7: one cast record serves both directions.
                "helloCast" to
                    RecordCodec.encode(
                        Hello(t = RecordType.HELLO, v = RECORD_VERSION, ent = true, cast = CastInfo(maxBytes = 2_048, rate = 4)),
                    ),
                "cast" to
                    RecordCodec.encode(
                        Cast(t = RecordType.CAST, scope = ByteArray(32) { it.toByte() }, data = ByteArray(16) { (0xa0 + it).toByte() }),
                    ),
            )
        for ((name, encoded) in vectors) {
            assertEquals(EXPECTED.getValue(name), encoded.toHex(), "record vector '$name' diverges from the spec")
        }
    }

    /**
     * §7.6's ticket vectors, signed with the RFC 8032 §7.1 TEST 1 key pair ([TicketVectorTest]
     * holds it), so the public half comes from the RFC rather than from this code. Ed25519 is
     * deterministic, so a signature is a vector like any other byte string.
     */
    @Test
    fun ticketVectorsMatchTheSpec() {
        assertEquals(EXPECTED.getValue("kid"), Tickets.kid(TicketVectorTest.RFC_PUBLIC).toHex())
        val (tk, sig) = Tickets.issue(TicketVectorTest.SIGNING_KEY, TicketVectorTest.fixture())
        assertEquals(EXPECTED.getValue("tk"), tk.toHex(), "ticket body diverges from the spec")
        assertEquals(EXPECTED.getValue("sig"), sig.toHex(), "ticket signature diverges from the spec")
        assertEquals(EXPECTED.getValue("ent"), RecordCodec.encode(Ent(t = RecordType.ENT, q = 7, tk = tk, sig = sig)).toHex())
        val (tkFiles, sigFiles) = Tickets.issue(TicketVectorTest.SIGNING_KEY, TicketVectorTest.filesFixture())
        assertEquals(EXPECTED.getValue("tkFiles"), tkFiles.toHex(), "files ticket body diverges from the spec")
        assertEquals(EXPECTED.getValue("sigFiles"), sigFiles.toHex(), "files ticket signature diverges from the spec")
        val cast =
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
        val (tkCast, sigCast) = Tickets.issue(TicketVectorTest.SIGNING_KEY, cast)
        assertEquals(EXPECTED.getValue("tkCast"), tkCast.toHex(), "cast ticket body diverges from the spec")
        assertEquals(EXPECTED.getValue("sigCast"), sigCast.toHex(), "cast ticket signature diverges from the spec")
        val (tkFilesAm, sigFilesAm) = Tickets.issue(TicketVectorTest.SIGNING_KEY, TicketVectorTest.amFixture(ab = 1_310_720L))
        assertEquals(EXPECTED.getValue("tkFilesAm"), tkFilesAm.toHex(), "am ticket body diverges from the spec")
        assertEquals(EXPECTED.getValue("sigFilesAm"), sigFilesAm.toHex(), "am ticket signature diverges from the spec")
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

    /**
     * §13's commons derivation rows: the invite's text form, the scope id the spool is given, and the
     * tag `hello` names a room by, for the room every older row uses and the second room the rooms
     * block adds. The content keys are the members' half and deliberately absent ([Commons]).
     */
    @Test
    fun commonsVectorsMatchTheSpec() {
        val secret = fixture(32, 10)
        assertEquals(EXPECTED.getValue("commonsSecret"), secret.toHex())
        assertEquals(EXPECTED.getValue("commonsInvite"), Commons.encodeInvite(secret))
        assertEquals(EXPECTED.getValue("commonsScopeId"), Commons.scopeId(secret).toHex())
        assertEquals(EXPECTED.getValue("commonsTag"), Commons.tag(Commons.scopeId(secret)).toHex())
        val secret2 = fixture(32, 11)
        assertEquals(EXPECTED.getValue("commonsSecret2"), secret2.toHex())
        assertEquals(EXPECTED.getValue("commonsScopeId2"), Commons.scopeId(secret2).toHex())
        assertEquals(EXPECTED.getValue("commonsTag2"), Commons.tag(Commons.scopeId(secret2)).toHex())
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

    internal companion object {
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
                "helloSpoolAttach" to
                    "a561746568656c6c6f617601636d696e01666c696d697473a9676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c84006e6d617841747461636842797465731a01000000696d6178414368756e6b19" +
                    "c045676d617841676574182067706f774269747314",
                "helloSpoolCommons" to
                    "a661746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c840067706f77426974731467636f6d6d6f6e73a4646e616d6564486f6d65696d61" +
                    "784672616d65731901f46574746c4d731a05265c00676d6178426c6f621a00010000",
                "helloSpoolCommonsAttach" to
                    "a361746568656c6c6f61760167636f6d6d6f6e73a4696d61784672616d65731901f4" +
                    "6574746c4d731a05265c00676d6178426c6f621a0001000066617474616368f5",
                "helloSpoolRooms" to
                    "a461746568656c6c6f61760167636f6d6d6f6e73a4646e616d6564486f6d65696d61784672616d65731901" +
                    "f46574746c4d731a05265c00676d6178426c6f621a0001000065726f6f6d7382a56374616748b15333db" +
                    "903e9745646e616d6564486f6d65696d61784672616d65731901f46574746c4d731a05265c00676d6178" +
                    "426c6f621a00010000a56374616748a2f73b72abeabcb1696d61784672616d657318c86574746c4d731a" +
                    "05265c00676d6178426c6f621a0001000066617474616368f5",
                "helloSpoolModeration" to
                    "a661746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c840067706f7742697473146a6d6f6465726174696f6ef5",
                "ahave" to
                    "a461746561686176656171056573636f7065582001080f161d242b323940474e555c" +
                    "636a71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f46" +
                    "4d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0",
                "ahas" to
                    "a6617464616861736171056573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d" +
                    "545b626970777e858c939aa1a8afb6bdc4cbd2d9e065746f74616c03646269747341" +
                    "09",
                "ahasDead" to
                    "a7617464616861736171056573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d" +
                    "545b626970777e858c939aa1a8afb6bdc4cbd2d9e065746f74616c00646269747340" +
                    "6464656164f5",
                "aget" to
                    "a6617464616765746171066573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d" +
                    "545b626970777e858c939aa1a8afb6bdc4cbd2d9e06466726f6d00616e02",
                "achunk" to
                    "a7617466616368756e6b6573636f7065582001080f161d242b323940474e555c636a" +
                    "71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d54" +
                    "5b626970777e858c939aa1a8afb6bdc4cbd2d9e0636964780165746f74616c036363" +
                    "69645820080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3" +
                    "dae164646174615830060d141b222930373e454c535a61686f767d848b9299a0a7ae" +
                    "b5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f",
                "aput" to
                    "a9617464617075746171076573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da636169645820070e151c232a31383f464d" +
                    "545b626970777e858c939aa1a8afb6bdc4cbd2d9e0636964780165746f74616c0363" +
                    "6369645820080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5cc" +
                    "d3dae164646174615830060d141b222930373e454c535a61686f767d848b9299a0a7" +
                    "aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f63706f77a2616e182a61" +
                    "641950c8",
                "helloSpoolTurn" to
                    "a661746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c840067706f774269747314647475726ef5",
                "turnreq" to "a26174677475726e726571617107",
                // knit-next 78d5fe633, §13, line for line.
                "helloSpoolTurnHosts" to
                    "a761746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a00010000696d" +
                    "61785265636f72641a00020000696d617853636f7065731840676d617850756c6c18406c6d617846" +
                    "72616d65734361701903e8686d617854746c4d731a240c840067706f774269747314647475726ef5" +
                    "697475726e486f737473827273706f6f6c2e6578616d706c653a33343738725b323030313a646238" +
                    "3a3a375d3a33343738",
                "turnreqHost" to "a36174677475726e72657161710764686f7374725b323030313a6462383a3a375d3a33343738",
                "turncred" to
                    "a66174687475726e637265646171076475726c738278257475726e3a73706f6f6c2e" +
                    "6578616d706c653a333437383f7472616e73706f72743d75647078267475726e733a" +
                    "73706f6f6c2e6578616d706c653a353334393f7472616e73706f72743d7463706475" +
                    "736572781b313830303030303030303a626d6a6273696268667932747971326b6470" +
                    "617373781c79563138432b4c634c36626949664c375a564f7556397856756f553d63" +
                    "6578701a6b49d200",
                "helloEntStun" to
                    "a461746568656c6c6f61760163656e74f5647374756e81757374756e3a6c61622e65" +
                    "78616d706c653a33343738",
                "helloEntGates" to "a461746568656c6c6f61760163656e74f568656e7447617465730b",
                "helloCast" to "a461746568656c6c6f61760163656e74f56463617374a2686d61784279746573190800647261746504",
                "cast" to
                    "a3617464636173746573636f70655820000102030405060708090a0b0c0d0e0f1011" +
                    "12131415161718191a1b1c1d1e1f646461746150a0a1a2a3a4a5a6a7a8a9aaabacad" +
                    "aeaf",
                "kid" to "21fe31dfa154a261",
                "tk" to
                    "a7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c6563" +
                    "6e62661a6b49d200636578701a6b4b238062707350000102030405060708090a0b0c" +
                    "0d0e0f62666c01",
                "sig" to
                    "c0740d14d5f46f2771721c593fa34a6e926b39182955cc6f8fb15365dddcf229" +
                    "da2cb33bdf6f23881cf4b153eedc4238e143fa53ef1a834961040bb55818b809",
                "ent" to
                    "a4617463656e7461710762746b584ba7617601636b69644821fe31dfa154a2616361" +
                    "75646b6c61622e6578616d706c65636e62661a6b49d200636578701a6b4b23806270" +
                    "7350000102030405060708090a0b0c0d0e0f62666c01637369675840c0740d14d5f4" +
                    "6f2771721c593fa34a6e926b39182955cc6f8fb15365dddcf229da2cb33bdf6f2388" +
                    "1cf4b153eedc4238e143fa53ef1a834961040bb55818b809",
                "tkFiles" to
                    "a8617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c6563" +
                    "6e62661a6b49d200636578701a6b4b238062707350000102030405060708090a0b0c" +
                    "0d0e0f62666c036261621a08000000",
                "sigFiles" to
                    "1942b4b07f1ef10f7e4061e822d909876b2e5a1597469c5aa70c87c5909dbc9c" +
                    "c24dc7703dfcb8a33ea190b19e4b9a9f460319f9ec2d11d17e787afe551b5d0a",
                "tkCast" to
                    "a7617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c6563" +
                    "6e62661a6b49d200636578701a6b4b238062707350000102030405060708090a0b0c" +
                    "0d0e0f62666c09",
                "sigCast" to
                    "9f0d82c5e15ddc486fc693a848dd2e937df32e505f22631dde6a49f82435d147" +
                    "b64da30b5ac432ef18ee9e2349c4c8915cc8e9d5a3e829f95c2784bc5a5f530f",
                // S-7.6-31's `am`, copied from knit-next ffabc0fc (docs/SPOOL_PROTOCOL.md §13).
                "tkFilesAm" to
                    "a9617601636b69644821fe31dfa154a261636175646b6c61622e6578616d706c6563" +
                    "6e62661a6b49d200636578701a6b4b238062707350000102030405060708090a0b0c" +
                    "0d0e0f62666c026261621a0014000062616d1a00040000",
                "sigFilesAm" to
                    "6ca646d923b0d93996c771aed64d590d8fc515214eb04ec01cbc7ad838863e45" +
                    "c60d831eaa035258542758592712cd7a33e23f7969884f6ae9585053275bc000",
                // §7.4's derivation rows, and the rooms block appended 2026-10-24 (knit-next 9451527).
                "commonsSecret" to "0a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3",
                "commonsInvite" to "knit-commons:v1:ChEYHyYtNDtCSVBXXmVsc3qBiI-WnaSrsrnAx87V3OM",
                "commonsScopeId" to "a081eddb259895d4b9e26b3142e5ce0d88e08721aed57f9ead6e6eb99b10edd6",
                "commonsTag" to "b15333db903e9745",
                "commonsSecret2" to "0b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dde4",
                "commonsScopeId2" to "ae34feaf814400c10160a8351729da34ee3c4b48ab69ecfca5eb9d168bf2c29e",
                "commonsTag2" to "a2f73b72abeabcb1",
            )
    }
}
