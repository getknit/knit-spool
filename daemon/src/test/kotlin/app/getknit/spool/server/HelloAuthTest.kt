// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.CloseCode
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.Ok
import app.getknit.spool.protocol.Pull
import app.getknit.spool.protocol.RecordCodec
import app.getknit.spool.protocol.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Connection establishment per spec §7.1: hello negotiation, auth, close codes. */
class HelloAuthTest {
    @Test
    fun serverHelloAdvertisesConfiguredLimits() {
        val config = testConfig()
        withServer(config) {
            connect {
                val hello = helloHandshake()
                assertEquals(1, hello.v)
                assertEquals(1, hello.min)
                assertEquals(0, hello.powBits)
                val limits = hello.limits!!
                assertEquals(config.hardLimits.maxBlob, limits.maxBlob)
                assertEquals(config.maxRecord, limits.maxRecord)
                assertEquals(config.hardLimits.maxScopes, limits.maxScopes)
                assertEquals(config.maxPull, limits.maxPull)
                assertEquals(config.hardLimits.maxFramesCap, limits.maxFramesCap)
                assertEquals(config.hardLimits.maxTtlMs, limits.maxTtlMs)
            }
        }
    }

    /**
     * Spec §7.5, S-7.5-1: omitted is the off state and `false` is never sent. Checked on the bytes
     * as well as the decoded record — the bytes are what a third-party client sees, and the key's
     * absence, not a `false`, is what the spec promises. It also keeps an off spool's `hello`
     * byte-identical to the §13 `helloSpool` vector.
     */
    @Test
    fun helloOmitsModerationByDefault() {
        withServer(testConfig()) {
            connect {
                val hello = helloHandshake()
                assertNull(hello.moderation)
                assertFalse(
                    RecordCodec.encode(hello).containsBytes("moderation".toByteArray()),
                    "the server hello carried a moderation key on a spool that does not require it",
                )
            }
        }
    }

    @Test
    fun helloAdvertisesModerationWhenRequired() {
        withServer(testConfig(requireModeration = true)) {
            connect {
                assertEquals(true, helloHandshake().moderation)
            }
        }
    }

    @Test
    fun tokenMismatchCloses4001() {
        withServer(testConfig(token = "s3cret")) {
            connect(token = "wrong") {
                awaitClose(CloseCode.AUTH)
            }
            connect {
                awaitClose(CloseCode.AUTH)
            }
        }
    }

    @Test
    fun tokenMatchProceeds() {
        withServer(testConfig(token = "s3cret")) {
            connect(token = "s3cret") {
                helloHandshake()
                subscribe(testScope(1))
            }
        }
    }

    /**
     * The point of a second credential: during a rotation both halves work, so no client is refused
     * while it migrates. Anything that is neither is still refused — a spool mid-rotation accepts
     * two secrets, not any secret.
     */
    @Test
    fun bothTokensAreAcceptedDuringRotation() {
        withServer(testConfig(token = "old", tokenNext = "new")) {
            connect(token = "old") {
                helloHandshake()
                subscribe(testScope(1))
            }
            connect(token = "new") {
                helloHandshake()
                subscribe(testScope(2))
            }
            connect(token = "neither") {
                awaitClose(CloseCode.AUTH)
            }
            connect {
                awaitClose(CloseCode.AUTH)
            }
        }
    }

    /**
     * Retiring the old half is what ends a rotation, and it has to actually end it: a client still
     * presenting the retired credential must be refused, or the rotation bought nothing.
     */
    @Test
    fun promotingNextRetiresTheOldToken() {
        withServer(testConfig(token = "new")) {
            connect(token = "new") {
                helloHandshake()
                subscribe(testScope(1))
            }
            connect(token = "old") {
                awaitClose(CloseCode.AUTH)
            }
        }
    }

    @Test
    fun nonHelloFirstCloses4000() {
        withServer {
            connect {
                expectRecord<Hello>(RecordType.HELLO)
                sendRecord(Pull(t = RecordType.PULL, q = 1L, scope = testScope(1), blobIds = emptyList()))
                awaitClose(CloseCode.MALFORMED)
            }
        }
    }

    @Test
    fun versionOutOfRangeCloses4002() {
        withServer {
            connect {
                expectRecord<Hello>(RecordType.HELLO)
                sendRecord(Hello(t = RecordType.HELLO, v = 99))
                awaitClose(CloseCode.VERSION)
            }
        }
    }

    @Test
    fun secondHelloErrsMalformedAndKeepsTheConnection() {
        withServer {
            connect {
                helloHandshake()
                sendRecord(Hello(t = RecordType.HELLO, v = 1))
                assertEquals(ErrCode.MALFORMED, expectRecord<Err>(RecordType.ERR).code)
                subscribe(testScope(1))
            }
        }
    }

    @Test
    fun unknownRecordTypeIsSkipped() {
        withServer {
            connect {
                helloHandshake()
                sendRecord(Ok(t = "x-future", q = 9L))
                subscribe(testScope(1))
            }
        }
    }

    @Test
    fun oversizeRecordErrsTooLarge() {
        withServer(testConfig(maxRecord = 256)) {
            connect {
                helloHandshake()
                sendRaw(ByteArray(300))
                assertEquals(ErrCode.TOO_LARGE, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }
}
