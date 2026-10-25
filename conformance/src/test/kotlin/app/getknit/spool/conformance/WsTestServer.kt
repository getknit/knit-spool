// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.conformance

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/**
 * A WebSocket server written by hand for the runner's own tests. No ktor server, so a test can
 * script a spool record by record or reset a connection mid-write, which is what the suite has to
 * survive against a real one. [onConnection] runs on a thread of its own per connection, numbered
 * from 1; whatever it throws ends that connection only.
 */
internal class WsTestServer(
    private val onConnection: (number: Int, conn: WsConn) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0)

    /** Connections accepted so far. */
    @Volatile
    var connections = 0
        private set

    val url: String get() = "ws://127.0.0.1:${server.localPort}/spool/v1"

    init {
        thread(isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val number = ++connections
                thread(isDaemon = true) {
                    runCatching { onConnection(number, WsConn.accept(socket)) }
                    runCatching { socket.close() }
                }
            }
        }
    }

    override fun close() = server.close()
}

/** One accepted connection: unmasked frames out, masked client frames in (RFC 6455 §5.2). */
internal class WsConn private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
) {
    /** Sends [payload] as one final binary frame. */
    fun sendBinary(payload: ByteArray) {
        val header =
            when {
                payload.size < LEN_16 -> byteArrayOf(FIN_BINARY, payload.size.toByte())
                payload.size <= 0xFFFF -> byteArrayOf(FIN_BINARY, LEN_16.toByte(), (payload.size shr 8).toByte(), payload.size.toByte())
                else -> error("test frames stay under 64 KiB")
            }
        socket.getOutputStream().apply {
            write(header + payload)
            flush()
        }
    }

    /** The next binary frame's payload, or null once the client sends a close or the socket ends. */
    fun receiveBinary(): ByteArray? {
        while (true) {
            val first = input.read().takeIf { it >= 0 } ?: return null
            val second = input.readUnsignedByte()
            val length =
                when (val len7 = second and LEN_MASK) {
                    LEN_16 -> input.readUnsignedShort().toLong()
                    LEN_64 -> input.readLong()
                    else -> len7.toLong()
                }
            val mask = ByteArray(MASK_BYTES).also { input.readFully(it) }
            val payload = ByteArray(length.toInt()).also { input.readFully(it) }
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % MASK_BYTES].toInt()).toByte()
            when (first and OPCODE_MASK) {
                OPCODE_BINARY -> return payload
                OPCODE_CLOSE -> return null
                else -> Unit // text, ping, pong: not what these tests script
            }
        }
    }

    /** Resets the connection: SO_LINGER 0 sends an RST, as a closed socket with unread data does. */
    fun reset() {
        socket.setSoLinger(true, 0)
        socket.close()
    }

    /** True when nothing more arrives from the client within [ms]; a byte that does is consumed. */
    fun quietFor(ms: Long): Boolean {
        socket.soTimeout = ms.toInt()
        return try {
            input.read() < 0
        } catch (_: SocketTimeoutException) {
            true
        } finally {
            socket.soTimeout = 0
        }
    }

    /** Blocks until the client goes away, or the test ends. */
    fun awaitEnd() {
        runCatching { while (input.read() >= 0) Unit }
    }

    companion object {
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val FIN_BINARY: Byte = 0x82.toByte()
        private const val OPCODE_MASK = 0x0F
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val LEN_MASK = 0x7F
        private const val LEN_16 = 126
        private const val LEN_64 = 127
        private const val MASK_BYTES = 4

        /** Reads the upgrade request and answers 101, leaving the stream at the first frame. */
        fun accept(socket: Socket): WsConn {
            val input = socket.getInputStream()
            val key =
                readHeaders(input)
                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                    .substringAfter(':')
                    .trim()
            val accept =
                Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray()),
                )
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(),
                )
                flush()
            }
            return WsConn(socket, DataInputStream(input))
        }

        /** Header lines up to the blank one, read a byte at a time so no frame byte is buffered away. */
        private fun readHeaders(input: InputStream): List<String> {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("connection ended inside the upgrade request")
                if (b == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    if (text.isEmpty()) return lines
                    lines += text
                    line.clear()
                } else {
                    line.append(b.toChar())
                }
            }
        }
    }
}
