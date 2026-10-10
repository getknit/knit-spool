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

    // The attachment family (spec §7.3). Only ever seen by a spool that advertised the three
    // attachment limits in HELLO; one that did not is never sent them.
    const val AHAVE = "ahave"
    const val AHAS = "ahas"
    const val AGET = "aget"
    const val ACHUNK = "achunk"
    const val APUT = "aput"

    // TURN provisioning (experimental; knit-next docs/VOICE_CALLS.md §2, proposed spec §7.6). Only
    // ever sent to a spool whose HELLO carried `turn`.
    const val TURNREQ = "turnreq"
    const val TURNCRED = "turncred"

    // Entitlement tickets (experimental; knit-next docs/VOICE_CALLS.md §2.6). Only ever sent to a
    // spool whose HELLO carried `ent`.
    const val ENT = "ent"

    // Casts (experimental; knit-next docs/VOICE_CALLS.md §2.7, proposed spec §7.7): one name for both
    // directions, like `hello` and `list`. Only ever sent to a spool whose HELLO carried `cast`.
    const val CAST = "cast"
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

    /** An `aput` whose `cid` differs from the chunk already stored at that position (§6.5). */
    const val CONFLICT = "conflict"

    /**
     * An `ent` the spool will not accept, or a `turnreq` that needs a ticket the connection does not
     * hold (experimental, VOICE_CALLS.md §2.6). `msg` carries one [Tickets.Reason] word.
     */
    const val TICKET = "ticket"
}

/** WebSocket close codes for failures before or outside the record layer (spec §7.1). */
object CloseCode {
    const val MALFORMED = 4000
    const val AUTH = 4001
    const val VERSION = 4002
    const val ABUSE = 4003
}

const val RECORD_VERSION = 1

/**
 * The length of every wire id. Scope ids, blob ids, attachment ids (`aid`) and chunk ids (`cid`)
 * are all raw 32-byte strings (spec B-2-6; the `bstr32` of §7.2 and §7.3). The codec cannot
 * enforce it — a CBOR byte string of any length decodes into a `ByteArray` — so a receiver checks
 * `size == ID_BYTES` itself before an id becomes a key.
 */
const val ID_BYTES = 32

/**
 * The plaintext bytes of every attachment chunk but the last: `aChunkBytes` of §4.5 and §12.1,
 * structural and not tunable (C-4.5-3), because it is what makes a chunk's position derivable
 * without a manifest. An attachment of `|A|` bytes is exactly `total = ceil(|A| / A_CHUNK_BYTES)`
 * chunks, so a spool can derive from its own attachment quota the largest `total` any attachment
 * that fits it could declare, and refuse a larger one before a chunk is stored.
 */
const val A_CHUNK_BYTES = 49_152

@Serializable
class RecordHead(
    val t: String,
)

/**
 * The HELLO limits. The three attachment fields are the capability signal of spec §7.3: present
 * together, or absent together. A client must not send attachment records to a spool that omitted
 * them — an unknown record type is skipped without an answer, which would strand the client's `q`.
 */
@Serializable
class Limits(
    val maxBlob: Int,
    val maxRecord: Int,
    val maxScopes: Int,
    val maxPull: Int,
    val maxFramesCap: Int,
    val maxTtlMs: Long,
    val maxAttachBytes: Int? = null,
    val maxAChunk: Int? = null,
    val maxAget: Int? = null,
) {
    val attachments: Boolean get() = maxAttachBytes != null && maxAChunk != null && maxAget != null
}

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

/**
 * The commons advertisement (spec §7.4): this spool runs one shared, operator-declared scope that
 * any invite holder can join. Present iff the spool has a commons — the same present-or-absent
 * capability signal as the attachment triad in [Limits].
 *
 * Deliberately carries no scope id. The id is `SHA-256("knit/spool/v1/commons" ‖ secret)` and comes
 * from the invite, out of band; a spool that published it would turn a room only invite holders can
 * find into one anybody who connects can flood. What is advertised is only what a client needs to
 * decide whether to join and how to size itself: the bounds it will actually get.
 */
@Serializable
class CommonsInfo(
    val name: String? = null,
    val maxFrames: Int,
    val ttlMs: Long,
    val maxBlob: Int,
    val attach: Boolean = false,
)

/**
 * The first record in each direction (spec §7.1). Spool→client carries the negotiation and every
 * capability signal; client→spool is `t` and the chosen `v`, nothing identifying.
 *
 * [moderation] is the send-side moderation request of spec §7.5: `true` asks clients to run their
 * on-device content screen before sending and to withhold what it flags. Nullable rather than
 * defaulted to `false` so a decoder can tell the field apart from its absence — the spec says a
 * spool that does not require it omits the field, and `false` on the wire is a conformance finding.
 * It is a request, not a check: the spool holds ciphertext and cannot verify compliance.
 *
 * [turn] says the spool issues TURN credentials ([TurnReq]/[TurnCred]; experimental, proposed spec
 * §7.6). Nullable for [moderation]'s reason: presence is the signal, and `false` is never sent.
 *
 * [ent] says the spool accepts entitlement tickets ([Ent]), and that a `turnreq` on this connection
 * needs one. [stun] lists `stun:`/`stuns:` URIs any client may gather from, with no credential — what
 * lets a phone with no TURN of its own be reached through someone else's relay. Both experimental
 * (VOICE_CALLS.md §2.6), and both omitted when off: `false` and an empty list are never sent.
 *
 * [cast] says the spool fans out [Cast]s, and within what (experimental, VOICE_CALLS.md §2.7). Omitted
 * when casts are off. Beside [ent], a cast is admitted only on a scope a cast ticket has unlocked.
 */
@Serializable
class Hello(
    val t: String,
    val v: Int,
    val min: Int? = null,
    val limits: Limits? = null,
    val powBits: Int? = null,
    val commons: CommonsInfo? = null,
    val moderation: Boolean? = null,
    val turn: Boolean? = null,
    val ent: Boolean? = null,
    val stun: List<String>? = null,
    val cast: CastInfo? = null,
)

/**
 * The cast advertisement (experimental, VOICE_CALLS.md §2.7, proposed spec §7.7): this spool fans
 * casts out. [maxBytes] bounds a cast's `data`, and [rate] is casts per second per connection, with a
 * burst of four times that. Both are stated up front because a cast is never answered: a client that
 * overshoots either is dropped without being told, so it has to know the bounds before it sends.
 */
@Serializable
class CastInfo(
    val maxBytes: Int,
    val rate: Int,
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

/** Client→spool: what does this spool hold for one attachment (§7.3)? Answered by [Ahas]. */
@Serializable
class Ahave(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val aid: ByteArray,
)

/**
 * Spool→client: one attachment's presence. [total] is 0 when unknown, [dead] when tombstoned, and
 * [bits] is the presence bitmap — chunk *i* is bit *i % 8*, MSB-first, of byte *i / 8*.
 */
@Serializable
class Ahas(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val aid: ByteArray,
    val total: Int,
    val bits: ByteArray,
    val dead: Boolean = false,
)

/**
 * Client→spool: ask for one TURN credential (experimental, proposed spec §7.6). Names no scope on
 * purpose — the spool must not learn which conversation is calling. Answered by [TurnCred] or `err`.
 */
@Serializable
class TurnReq(
    val t: String,
    val q: Long,
)

/**
 * Spool→client: a coturn REST credential. [user] is `"<exp>:<random>"`, [pass] is
 * `base64(HMAC-SHA1(secret, user))`, [exp] is Unix **seconds**, and [urls] are RFC 7065
 * `turn:`/`turns:` URIs. Terminal, and echoes [q] like [Ahas].
 */
@Serializable
class TurnCred(
    val t: String,
    val q: Long,
    val urls: List<String>,
    val user: String,
    val pass: String,
    val exp: Long,
)

/**
 * Client→spool: present an entitlement ticket (experimental, VOICE_CALLS.md §2.6). [tk] is the CBOR
 * [Ticket] exactly as signed, and [sig] the 64-byte Ed25519 signature over [Tickets.LABEL] ‖ [tk] —
 * carried as bytes rather than re-encoded, so the spool verifies what the issuer signed. Answered by
 * `ok` or `err ticket`; a later `ent` on the same connection replaces the claims.
 */
@Serializable
class Ent(
    val t: String,
    val q: Long,
    val tk: ByteArray,
    val sig: ByteArray,
)

/**
 * A cast (experimental, VOICE_CALLS.md §2.7, proposed spec §7.7): one sealed frame for a scope's other
 * live subscribers, never stored. Client→spool, it is the one client record with no `q`, and it is
 * never answered — a refusal is a silent drop — because a shipped client reads a scoped `err` without
 * a `q` as a refused `sub`. Spool→client it is relayed unchanged, uploader excluded, like an [Event]
 * with no blob id: nothing about it is content-addressed, because nothing is kept.
 */
@Serializable
class Cast(
    val t: String,
    val scope: ByteArray,
    val data: ByteArray,
)

/** Client→spool: fetch up to [n] chunks from [from] (truncated at `maxAget`); [Achunk]s then a bare OK. */
@Serializable
class Aget(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val aid: ByteArray,
    val from: Int,
    val n: Int,
)

/** Spool→client: one sealed attachment chunk. No `q` — attributed like [Blob]. */
@Serializable
class Achunk(
    val t: String,
    val scope: ByteArray,
    val aid: ByteArray,
    val idx: Int,
    val total: Int,
    val cid: ByteArray,
    val data: ByteArray,
)

/** Client→spool: store one sealed chunk. `cid` re-verified; first write wins at a position. */
@Serializable
class Aput(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val aid: ByteArray,
    val idx: Int,
    val total: Int,
    val cid: ByteArray,
    val data: ByteArray,
    val pow: PowStamp? = null,
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
