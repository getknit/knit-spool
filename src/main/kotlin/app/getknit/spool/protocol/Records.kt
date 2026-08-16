// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The client↔spool record layer, implementing SPOOL_PROTOCOL.md §7 (the Knit repo's
 * `docs/SPOOL_PROTOCOL.md` is normative; this file is an implementation of it, and the §13 record
 * vectors are pinned byte-exact by `SpecVectorTest`). One CBOR record per WebSocket binary message;
 * a plain string discriminator `t`; unknown `t` is skipped and unknown fields are ignored — the
 * additive-evolution contract.
 */
object RecordType {
    const val HELLO = "hello"
    const val SUB = "sub"
    const val DIGEST = "digest"
    const val LIST = "list"
    const val PULL = "pull"
    const val BLOB = "blob"
    const val PUSH = "push"
    const val EVENT = "event"
    const val OK = "ok"
    const val ERR = "err"
}

/** Error codes (append-only registry, spec §7.2). */
object ErrCode {
    const val VERSION = "version"
    const val POW = "pow"
    const val TOMBSTONED = "tombstoned"
    const val QUOTA = "quota"
    const val TOO_LARGE = "too_large"
    const val BAD_ID = "bad_id"
    const val RATE = "rate"
    const val NOT_SUBSCRIBED = "not_subscribed"
    const val MALFORMED = "malformed"
    const val INTERNAL = "internal"
}

/** WebSocket close codes for failures before or outside the record layer (spec §7.1). */
object CloseCode {
    const val MALFORMED = 4000
    const val AUTH = 4001
    const val VERSION = 4002
    const val ABUSE = 4003
}

const val RECORD_VERSION = 1

@Serializable
class RecordHead(
    val t: String,
)

@Serializable
class Limits(
    val maxBlob: Int,
    val maxRecord: Int,
    val maxScopes: Int,
    val maxPull: Int,
    val maxFramesCap: Int,
    val maxTtlMs: Long,
)

@Serializable
class PowStamp(
    val n: Long,
    val d: Long,
)

@Serializable
class ScopeBounds(
    val maxFrames: Int,
    val ttlMs: Long,
    val maxBlob: Int,
)

@Serializable
class ScopeSub(
    val scope: ByteArray,
    val bounds: ScopeBounds,
    val pow: PowStamp? = null,
)

@Serializable
class Hello(
    val t: String,
    val v: Int,
    val min: Int? = null,
    val limits: Limits? = null,
    val powBits: Int? = null,
)

@Serializable
class Sub(
    val t: String,
    val q: Long,
    val subs: List<ScopeSub>,
)

@Serializable
class Digest(
    val t: String,
    val scope: ByteArray,
    val digest: ByteArray,
    val count: Int,
    val full: Boolean,
    val bounds: ScopeBounds,
)

@Serializable
class ScopeList(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val blobIds: List<ByteArray>? = null,
    val tombstones: List<ByteArray>? = null,
)

@Serializable
class Pull(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val blobIds: List<ByteArray>,
)

@Serializable
class Blob(
    val t: String,
    val scope: ByteArray,
    val blobId: ByteArray,
    val data: ByteArray,
)

@Serializable
class Push(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val blobId: ByteArray,
    val data: ByteArray,
    val pow: PowStamp? = null,
)

@Serializable
class Event(
    val t: String,
    val scope: ByteArray,
    val blobId: ByteArray,
    val data: ByteArray,
)

@Serializable
class Ok(
    val t: String,
    val q: Long,
    val missing: List<ByteArray>? = null,
)

@Serializable
class Err(
    val t: String,
    val code: String,
    val q: Long? = null,
    val scope: ByteArray? = null,
    val msg: String? = null,
    val retryMs: Long? = null,
)

/**
 * The record codec: the spec §2 CBOR profile — definite-length, unknown-tolerant, defaults omitted,
 * every `ByteArray` (including list elements) a CBOR byte string.
 */
@OptIn(ExperimentalSerializationApi::class)
object RecordCodec {
    val cbor: Cbor =
        Cbor {
            ignoreUnknownKeys = true
            encodeDefaults = false
            useDefiniteLengthEncoding = true
            alwaysUseByteString = true
        }

    inline fun <reified T> encode(record: T): ByteArray = cbor.encodeToByteArray(record)

    inline fun <reified T> decode(bytes: ByteArray): T? = runCatching { cbor.decodeFromByteArray<T>(bytes) }.getOrNull()

    fun peekType(bytes: ByteArray): String? = decode<RecordHead>(bytes)?.t
}
