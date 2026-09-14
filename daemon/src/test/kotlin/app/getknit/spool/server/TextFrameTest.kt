// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.server

import app.getknit.spool.protocol.CloseCode
import app.getknit.spool.protocol.Err
import app.getknit.spool.protocol.ErrCode
import app.getknit.spool.protocol.Hello
import app.getknit.spool.protocol.RecordType
import io.ktor.websocket.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A record is a binary message (B-7.1-4). A text frame is malformed traffic and is charged as such:
 * close 4000 before hello, a record token and `err malformed` after.
 */
class TextFrameTest {
    @Test
    fun textFrameBeforeHelloCloses4000() {
        withServer {
            connect {
                expectRecord<Hello>(RecordType.HELLO)
                send(Frame.Text("hello"))
                awaitClose(CloseCode.MALFORMED)
            }
        }
    }

    @Test
    fun textFrameAfterHelloIsMalformedAndTheConnectionKeepsWorking() {
        withServer {
            connect {
                helloHandshake()
                send(Frame.Text("not a record"))
                val err = expectRecord<Err>(RecordType.ERR)
                assertEquals(ErrCode.MALFORMED, err.code)
                assertNull(err.q, "a frame that is not a record carries no q to echo")
                subscribe(testScope(1))
            }
        }
    }

    @Test
    fun textFramesSpendRecordTokens() {
        withServer(testConfig(rateRecords = 2)) {
            // burst 8; the fake clock never refills
            connect {
                helloHandshake()
                repeat(8) {
                    send(Frame.Text("x"))
                    assertEquals(ErrCode.MALFORMED, expectRecord<Err>(RecordType.ERR).code)
                }
                send(Frame.Text("x"))
                assertEquals(ErrCode.RATE, expectRecord<Err>(RecordType.ERR).code)
            }
        }
    }
}
