// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.server.SpoolServer
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.SqliteScopeStore
import org.slf4j.LoggerFactory
import sun.misc.Signal
import sun.misc.SignalHandler
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.system.exitProcess

/**
 * knit-spool — a scoped, blinded store-and-forward relay for the Knit mesh messenger's Internet
 * plane. Normative protocol: the Knit repo's docs/SPOOL_PROTOCOL.md. Configuration is environment
 * variables only (container-first); see the README's table. Invalid values fail fast — a spool
 * that silently ran on defaults because of a typo is worse than one that refuses to start.
 */
private val log = LoggerFactory.getLogger("app.getknit.spool.Main")

internal val KNOWN_VARS =
    setOf(
        "SPOOL_PORT",
        "SPOOL_TOKEN",
        "SPOOL_TOKEN_NEXT",
        "SPOOL_METRICS_TOKEN",
        "SPOOL_POW_BITS",
        "SPOOL_MAX_BLOB",
        "SPOOL_MAX_SCOPES",
        "SPOOL_MAX_FRAMES",
        "SPOOL_MAX_TTL_MS",
        "SPOOL_MAX_RECORD",
        "SPOOL_MAX_PULL",
        "SPOOL_MAX_AGET",
        "SPOOL_MAX_ATTACH_BYTES",
        "SPOOL_MAX_A_CHUNK",
        "SPOOL_MAX_BYTES",
        "SPOOL_SWEEP_MS",
        "SPOOL_STATUS_MS",
        "SPOOL_TRUST_PROXY",
        "SPOOL_MAX_CONNS",
        "SPOOL_MAX_CONNS_PER_IP",
        "SPOOL_RATE_RECORDS",
        "SPOOL_RATE_PUSHES",
        "SPOOL_RATE_NEW_SCOPES",
        "SPOOL_LOG_LEVEL",
        "SPOOL_SOURCE_URL",
        "SPOOL_DATA_DIR",
        "SPOOL_RELOAD_FILE",
        "SPOOL_COMMONS_ID",
        "SPOOL_COMMONS_NAME",
        "SPOOL_COMMONS_MAX_FRAMES",
        "SPOOL_COMMONS_TTL_MS",
        "SPOOL_COMMONS_MAX_BLOB",
        "SPOOL_COMMONS_ATTACH",
        "SPOOL_COMMONS_RATE_PUSHES",
    )

/**
 * A `list` reply carries every live id and every tombstone in one record, so a scope's frame cap
 * and `SPOOL_MAX_RECORD` are coupled. 32 id bytes plus two of CBOR header, and [LIST_ENVELOPE] for
 * the record around them.
 */
private const val ID_RECORD_BYTES = 34

private const val LIST_ENVELOPE = 512

private val HEX_64 = Regex("[0-9a-fA-F]{64}")

/**
 * What a §13 source offer is allowed to look like: an http(s) URL with nothing in it that could
 * break out of the JSON string or the Prometheus label it is rendered into. Both render sites
 * escape anyway — this is the fail-fast half, because a source offer that does not resolve is a
 * licence failure, and hearing about it from a user is worse than hearing about it at boot.
 */
private val SOURCE_URL = Regex("""https?://[^\s"'\\<>]+""")

fun main(args: Array<String>) {
    when (val command = args.firstOrNull()) {
        null -> {
            serve()
        }

        "commons-invite" -> {
            printCommonsInvite()
        }

        "check" -> {
            exitProcess(checkConfig(System.getenv()))
        }

        else -> {
            System.err.println("unknown command \"$command\"; usage: knit-spool [commons-invite|check]")
            exitProcess(2)
        }
    }
}

/**
 * Mints a commons invite and prints both halves: the secret for members, and the hash of it for
 * `SPOOL_COMMONS_ID`.
 *
 * Written to stdout and nowhere else — never logged, never persisted. The daemon that later serves
 * this commons is given only the hash, so it never holds the key to the room it relays.
 */
private fun printCommonsInvite() {
    val secret = ByteArray(Commons.SECRET_BYTES)
    SecureRandom().nextBytes(secret)
    println("invite (give this to members):  ${Commons.encodeInvite(secret)}")
    println("spool config (put in env):      SPOOL_COMMONS_ID=${hex(Commons.scopeId(secret))}")
}

/** `SPOOL_*` names nothing reads — almost always a typo, and silence about them is worse. */
private fun unknownVars(environment: Map<String, String>): List<String> =
    environment.keys.filter { it.startsWith("SPOOL_") && it !in KNOWN_VARS }.sorted()

/**
 * Validates the environment and prints what it resolved to, then exits: 0 valid, 1 not.
 *
 * For provisioning, which wants to know a customer's configuration is good *before* a container
 * starts, and for testing a tier template in CI without launching a daemon and reading its logs.
 * Side-effect free by contract — it binds no port, opens no store, and creates no directory.
 *
 * What it therefore cannot tell you: whether the commons will actually be created. That fails at
 * boot only when a persistent store already holds `SPOOL_MAX_SCOPES` scopes, which needs the store
 * open to see. This checks configuration, not store state.
 *
 * The resolved config goes to stdout and everything else to stderr, so a caller can parse one
 * without filtering the other.
 */
internal fun checkConfig(environment: Map<String, String>): Int {
    unknownVars(environment).forEach { System.err.println("warning: unrecognized environment variable $it — typo?") }
    val config =
        try {
            configFromEnv(environment::get)
        } catch (e: IllegalArgumentException) {
            System.err.println("invalid configuration: ${e.message}")
            return 1
        }
    val dataDir = environment["SPOOL_DATA_DIR"]?.takeIf { it.isNotEmpty() }
    val store =
        if (dataDir == null) {
            "memory"
        } else {
            val path = Path.of(dataDir)
            // Reported, never created: `check` must be safe to run against a customer's environment
            // without leaving anything behind. The daemon creates it at boot; this only answers
            // whether it will be able to.
            val target = if (Files.isDirectory(path)) path else path.parent
            if (target == null || !Files.isDirectory(target) || !Files.isWritable(target)) {
                System.err.println("invalid configuration: SPOOL_DATA_DIR ($dataDir) is not in a writable directory")
                return 1
            }
            "sqlite:$dataDir"
        }
    println("${config.describe()} store=$store")
    return 0
}

/**
 * SIGUSR1 toggles draining: `docker kill --signal=USR1 <container>` closes the door to new
 * connections and leaves the live ones alone, and sending it again re-opens.
 *
 * SIGUSR1 rather than a second SIGTERM, which is what `docker stop` sends before it SIGKILLs — a
 * two-phase TERM would drain and then be killed mid-drain by the ordinary stop path.
 *
 * `sun.misc.Signal` is the only handle the JDK offers (module `jdk.unsupported`, no flags needed
 * on 21). A platform without SIGUSR1 loses the feature and keeps the daemon: refusing to start a
 * spool over a signal it will never receive would be the worse trade.
 */
private fun installDrainSignal(server: SpoolServer) {
    runCatching { Signal.handle(Signal("USR1")) { server.toggleDrain() } }
        .onFailure { log.warn("SIGUSR1 unavailable — drain mode cannot be toggled: {}", it.message) }
}

/**
 * Re-read the configuration on SIGHUP and install its reloadable half.
 *
 * The environment cannot be the source. `System.getenv()` is fixed at exec — a container's
 * variables cannot change without recreating it, which is the reconnect this exists to avoid — so
 * re-reading it would be an elaborate no-op. `SPOOL_RELOAD_FILE` names a file of `KEY=value` lines
 * in exactly the environment's own syntax, layered *over* the environment: anything the file names
 * wins, anything it omits falls back to the value the process booted with. Unsetting a variable is
 * therefore removing its line, not writing an empty one.
 *
 * A file that is missing, unreadable or invalid leaves the running configuration exactly as it is
 * and logs why. A reload is an operation that can be retried; a spool that dropped its quotas
 * because a config write was half-flushed is not.
 */
private fun installReloadSignal(
    server: SpoolServer,
    environment: Map<String, String>,
) {
    val path = environment["SPOOL_RELOAD_FILE"]?.takeIf { it.isNotEmpty() }
    val handler =
        SignalHandler {
            if (path == null) {
                log.warn("SIGHUP ignored — set SPOOL_RELOAD_FILE to the file a reload should read")
                return@SignalHandler
            }
            val overrides =
                try {
                    readEnvFile(Path.of(path))
                } catch (e: Exception) {
                    log.error("SIGHUP: cannot read {}: {} — configuration unchanged", path, e.message)
                    return@SignalHandler
                }
            val candidate =
                try {
                    configFromEnv { name -> overrides[name] ?: environment[name] }
                } catch (e: IllegalArgumentException) {
                    log.error("SIGHUP: invalid configuration: {} — configuration unchanged", e.message)
                    return@SignalHandler
                }
            server.reload(candidate).forEach { log.warn("SIGHUP: {} needs a restart — ignored", it) }
            log.info("reloaded configuration from {}: {}", path, candidate.reloadable().entries.joinToString(" ") { (k, v) -> "$k=$v" })
        }
    runCatching { Signal.handle(Signal("HUP"), handler) }
        .onFailure { log.warn("SIGHUP unavailable — configuration cannot be reloaded: {}", it.message) }
}

/**
 * `KEY=value` per line, `#` comments and blanks skipped, no quote or escape processing.
 *
 * Deliberately the same shape as the environment and nothing more: this file is written by
 * whatever provisions the spool, and a format with quoting rules is a format with a parser
 * disagreement waiting in it. A value is the rest of the line, trailing whitespace and all.
 */
internal fun readEnvFile(path: Path): Map<String, String> =
    Files
        .readAllLines(path)
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1)
        }.toMap()

private fun serve() {
    val environment = System.getenv()
    unknownVars(environment).forEach { log.warn("unrecognized environment variable {} — typo?", it) }
    val config =
        try {
            configFromEnv(environment::get)
        } catch (e: IllegalArgumentException) {
            log.error("invalid configuration: {}", e.message)
            exitProcess(1)
        }
    val dataDir = environment["SPOOL_DATA_DIR"]?.takeIf { it.isNotEmpty() }
    val store =
        try {
            if (dataDir == null) {
                log.info("no SPOOL_DATA_DIR — in-memory store (a restart drops every scope)")
                InMemoryScopeStore(config.hardLimits)
            } else {
                SqliteScopeStore.open(Path.of(dataDir), config.hardLimits)
            }
        } catch (e: Exception) {
            log.error("cannot open store: {}", e.message)
            exitProcess(1)
        }
    val server = SpoolServer(config, store)
    Runtime.getRuntime().addShutdownHook(Thread(server::stop, "spool-shutdown"))
    installDrainSignal(server)
    installReloadSignal(server, environment)
    try {
        server.start(wait = true)
    } catch (e: IllegalStateException) {
        // The commons could not be created — a startup condition the config parser cannot see,
        // because it depends on what a persistent store already holds.
        log.error("cannot start: {}", e.message)
        exitProcess(1)
    }
}

internal fun configFromEnv(env: (String) -> String?): SpoolServer.Config {
    val maxBlob = intVar(env, "SPOOL_MAX_BLOB", default = 65_536, min = 1)
    val maxRecord = intVar(env, "SPOOL_MAX_RECORD", default = 131_072, min = 1)
    require(maxBlob + 512 <= maxRecord) {
        "SPOOL_MAX_BLOB ($maxBlob) + 512 bytes of CBOR envelope must fit SPOOL_MAX_RECORD ($maxRecord)"
    }
    // 0 is the off switch; anything else is a cadence, and a sub-second one would flood the log
    // it exists to make readable.
    val statusMs = longVar(env, "SPOOL_STATUS_MS", default = 300_000L, min = 0L)
    require(statusMs == 0L || statusMs >= 1_000L) { "SPOOL_STATUS_MS must be 0 (off) or >= 1000, got $statusMs" }
    val maxConns = intVar(env, "SPOOL_MAX_CONNS", default = 0, min = 0)
    val maxConnsPerIp = intVar(env, "SPOOL_MAX_CONNS_PER_IP", default = 16, min = 1)
    // Legal but almost never meant: one address could fill the spool on its own, which is the
    // shape the per-IP cap exists to prevent. Warned, not refused — a one-connection test spool is
    // a real thing to want.
    if (maxConns in 1..<maxConnsPerIp) {
        log.warn(
            "SPOOL_MAX_CONNS_PER_IP ($maxConnsPerIp) is above SPOOL_MAX_CONNS ($maxConns): one client address can take the whole spool",
        )
    }
    val hardLimits =
        HardLimits(
            maxBlob = maxBlob,
            maxFramesCap = intVar(env, "SPOOL_MAX_FRAMES", default = 1_000, min = 1),
            maxTtlMs = longVar(env, "SPOOL_MAX_TTL_MS", default = 604_800_000L, min = 1L),
            maxScopes = intVar(env, "SPOOL_MAX_SCOPES", default = 64, min = 1),
            // 0 switches attachments off entirely; the server then omits all three limits from
            // HELLO and a conforming client never sends an attachment record (spec §7.3).
            maxAttachBytes = intVar(env, "SPOOL_MAX_ATTACH_BYTES", default = 16_777_216, min = 0),
            maxAChunk = intVar(env, "SPOOL_MAX_A_CHUNK", default = HardLimits.DEFAULT_MAX_A_CHUNK, min = 1),
        )
    val maxBytes = longVar(env, "SPOOL_MAX_BYTES", default = 268_435_456L, min = 0L)
    // Rotation, in the order an operator performs it: publish NEXT, let clients migrate, promote
    // it to TOKEN, unset NEXT. Both are accepted for as long as both are set.
    val token = env("SPOOL_TOKEN")?.takeIf { it.isNotEmpty() }
    val tokenNext = env("SPOOL_TOKEN_NEXT")?.takeIf { it.isNotEmpty() }
    // NEXT alone would work — it is just a second accepted credential — but it would mean an
    // operator had opened a spool believing they had gated it, or had finished a rotation by
    // clearing the wrong half. Neither is worth silently allowing.
    require(tokenNext == null || token != null) {
        "SPOOL_TOKEN_NEXT is set but SPOOL_TOKEN is not: set both during a rotation, then promote NEXT into TOKEN"
    }
    require(tokenNext == null || tokenNext != token) { "SPOOL_TOKEN_NEXT must differ from SPOOL_TOKEN" }
    return SpoolServer.Config(
        port = intVar(env, "SPOOL_PORT", default = 9470, min = 1, max = 65_535),
        token = token,
        tokenNext = tokenNext,
        metricsToken = env("SPOOL_METRICS_TOKEN")?.takeIf { it.isNotEmpty() },
        powBits = intVar(env, "SPOOL_POW_BITS", default = 0, min = 0, max = 30),
        maxRecord = maxRecord,
        maxPull = intVar(env, "SPOOL_MAX_PULL", default = 64, min = 1),
        maxAget = intVar(env, "SPOOL_MAX_AGET", default = 32, min = 1),
        hardLimits = hardLimits,
        maxBytes = maxBytes,
        sweepMs = longVar(env, "SPOOL_SWEEP_MS", default = 60_000L, min = 1_000L),
        statusMs = statusMs,
        trustProxy = boolVar(env, "SPOOL_TRUST_PROXY", default = false),
        maxConns = maxConns,
        maxConnsPerIp = maxConnsPerIp,
        rateRecords = intVar(env, "SPOOL_RATE_RECORDS", default = 50, min = 1),
        ratePushes = intVar(env, "SPOOL_RATE_PUSHES", default = 10, min = 1),
        rateNewScopesPerMin = intVar(env, "SPOOL_RATE_NEW_SCOPES", default = 6, min = 1),
        sourceUrl = sourceUrlFromEnv(env),
        commons = commonsFromEnv(env, hardLimits, maxRecord, maxBytes),
    )
}

/**
 * The corresponding-source URL served at `GET /source`.
 *
 * Defaults to upstream, which is truthful for an unmodified build and wrong for a fork: §13
 * obliges an operator to offer the source of *their* version, not of the code it diverged from.
 * A fork points this at its own repository and the offer becomes correct without touching a line
 * of Kotlin — the same reason every other knob here is an environment variable.
 */
internal fun sourceUrlFromEnv(env: (String) -> String?): String {
    val raw = env("SPOOL_SOURCE_URL")?.takeIf { it.isNotEmpty() } ?: return BuildInfo.UPSTREAM_SOURCE_URL
    require(SOURCE_URL.matches(raw)) { "SPOOL_SOURCE_URL must be an http(s) URL, got \"$raw\"" }
    return raw
}

/**
 * The commons (spec §7.4), or null when `SPOOL_COMMONS_ID` is unset — the whole feature is off by
 * default, and a spool without one behaves exactly as before.
 *
 * The id is `SHA-256("knit/spool/v1/commons" ‖ secret)`, minted by `knit-spool commons-invite`.
 * Only the hash is configured here: the secret goes to members, so the spool holds a room it cannot
 * read. Rotating the room means minting a new invite and restarting — the old scope is no longer
 * pinned and ages out on its own TTL or under the watermark.
 */
internal fun commonsFromEnv(
    env: (String) -> String?,
    hardLimits: HardLimits,
    maxRecord: Int,
    maxBytes: Long,
): SpoolServer.CommonsConfig? {
    val raw = env("SPOOL_COMMONS_ID")?.takeIf { it.isNotEmpty() } ?: return null
    // Never echoed in full: Main.serve logs this message at ERROR, and one mistyped character in
    // a real id would put a near-real scope id in the log the rest of this daemon keeps out.
    require(HEX_64.matches(raw)) {
        "SPOOL_COMMONS_ID must be 64 hex characters (a 32-byte scope id), got ${raw.length} " +
            "characters starting \"${raw.take(8)}\""
    }
    val maxFrames = intVar(env, "SPOOL_COMMONS_MAX_FRAMES", default = 500, min = 1)
    val ttlMs = longVar(env, "SPOOL_COMMONS_TTL_MS", default = 86_400_000L, min = 1L)
    val commonsMaxBlob = intVar(env, "SPOOL_COMMONS_MAX_BLOB", default = hardLimits.maxBlob, min = 1)
    val attach = boolVar(env, "SPOOL_COMMONS_ATTACH", default = false)

    // Inside the hard caps, so the store's subscribe-time clamp is a no-op and the bounds a client
    // is told about in `digest` are the bounds the operator configured.
    require(maxFrames <= hardLimits.maxFramesCap) {
        "SPOOL_COMMONS_MAX_FRAMES ($maxFrames) must not exceed SPOOL_MAX_FRAMES (${hardLimits.maxFramesCap})"
    }
    require(ttlMs <= hardLimits.maxTtlMs) {
        "SPOOL_COMMONS_TTL_MS ($ttlMs) must not exceed SPOOL_MAX_TTL_MS (${hardLimits.maxTtlMs})"
    }
    require(commonsMaxBlob <= hardLimits.maxBlob) {
        "SPOOL_COMMONS_MAX_BLOB ($commonsMaxBlob) must not exceed SPOOL_MAX_BLOB (${hardLimits.maxBlob})"
    }

    // A `list` for the commons must fit one record, or the transport kills it (1009) and the room
    // can never be caught up on. Tombstones ride the same reply, bounded by ScopeStore.tombstoneCap.
    val listBytes = (maxFrames.toLong() + maxOf(2L * maxFrames, 1024L)) * ID_RECORD_BYTES + LIST_ENVELOPE
    require(listBytes <= maxRecord) {
        "SPOOL_COMMONS_MAX_FRAMES ($maxFrames) needs a $listBytes-byte list reply, over " +
            "SPOOL_MAX_RECORD ($maxRecord); lower it or raise SPOOL_MAX_RECORD"
    }

    // The commons is pinned against the watermark, so a commons that alone exceeds SPOOL_MAX_BYTES
    // would leave the watermark with nothing it is allowed to shed. Caught here, not at 3am.
    if (maxBytes > 0) {
        val footprint =
            maxFrames.toLong() * commonsMaxBlob + if (attach) hardLimits.maxAttachBytes.toLong() else 0L
        require(footprint <= maxBytes) {
            "the commons can hold $footprint bytes ($maxFrames frames x $commonsMaxBlob" +
                (if (attach) " plus ${hardLimits.maxAttachBytes} of attachments" else "") +
                "), over SPOOL_MAX_BYTES ($maxBytes); it is pinned against the watermark, so it must fit"
        }
    }

    return SpoolServer.CommonsConfig(
        scopeId = unhex(raw),
        name = env("SPOOL_COMMONS_NAME")?.takeIf { it.isNotEmpty() },
        bounds = ScopeBounds(maxFrames = maxFrames, ttlMs = ttlMs, maxBlob = commonsMaxBlob),
        attach = attach,
        ratePushes = intVar(env, "SPOOL_COMMONS_RATE_PUSHES", default = 20, min = 1),
    )
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private fun intVar(
    env: (String) -> String?,
    name: String,
    default: Int,
    min: Int,
    max: Int = Int.MAX_VALUE,
): Int {
    val raw = env(name) ?: return default
    val value = requireNotNull(raw.toIntOrNull()) { "$name must be an integer, got \"$raw\"" }
    require(value in min..max) { "$name must be in $min..$max, got $value" }
    return value
}

private fun longVar(
    env: (String) -> String?,
    name: String,
    default: Long,
    min: Long,
): Long {
    val raw = env(name) ?: return default
    val value = requireNotNull(raw.toLongOrNull()) { "$name must be an integer, got \"$raw\"" }
    require(value >= min) { "$name must be >= $min, got $value" }
    return value
}

private fun boolVar(
    env: (String) -> String?,
    name: String,
    default: Boolean,
): Boolean =
    when (val raw = env(name)) {
        null, "" -> default
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("$name must be \"true\" or \"false\", got \"$raw\"")
    }
