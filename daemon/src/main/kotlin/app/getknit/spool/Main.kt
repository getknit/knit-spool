// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.protocol.Commons
import app.getknit.spool.protocol.ScopeBounds
import app.getknit.spool.protocol.Tickets
import app.getknit.spool.protocol.TurnUris
import app.getknit.spool.server.SpoolServer
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.SqliteScopeStore
import org.slf4j.LoggerFactory
import sun.misc.Signal
import sun.misc.SignalHandler
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import kotlin.system.exitProcess

/**
 * knit-spool — a scoped, blinded store-and-forward relay for the Knit mesh messenger's Internet
 * plane. Normative protocol: the Knit repo's docs/SPOOL_PROTOCOL.md. Configuration is environment
 * variables only (container-first); see the README's table. Invalid values fail fast — a spool
 * that silently ran on defaults because of a typo is worse than one that refuses to start.
 */
private val log = LoggerFactory.getLogger("app.getknit.spool.Main")

/**
 * What each room's variables are named after its `SPOOL_COMMONS_` or `SPOOL_COMMONS_<n>_` prefix.
 * Above [KNOWN_VARS], which reads it: top-level properties initialize in file order.
 */
private val COMMONS_ROOM_KEYS = listOf("ID", "NAME", "MAX_FRAMES", "TTL_MS", "MAX_BLOB", "ATTACH", "RATE_PUSHES")

/** What each TURN server's variables are named after its `SPOOL_TURN_` or `SPOOL_TURN_<n>_` prefix; above [KNOWN_VARS] too. */
private val TURN_SERVER_KEYS = listOf("SECRET", "URLS")

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
        "SPOOL_REQUIRE_MODERATION",
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
        "SPOOL_TURN_SECRET",
        "SPOOL_TURN_URLS",
        "SPOOL_TURN_TTL_S",
        "SPOOL_TURN_RATE",
        "SPOOL_STUN_URLS",
        "SPOOL_TICKET_KEY",
        "SPOOL_TICKET_KEY_NEXT",
        "SPOOL_TICKET_AUDIENCE",
        "SPOOL_TICKET_AUDIENCE_ALIAS",
        "SPOOL_TICKET_GATES",
        "SPOOL_TICKET_ATTACH_BYTES",
        "SPOOL_TICKET_TURN_RATE",
        "SPOOL_RATE_CASTS",
        "SPOOL_MAX_CAST",
        "SPOOL_RATE_CASTS_TOTAL",
        "SPOOL_COMMONS_RATE_TOTAL",
    ) + (2..MAX_COMMONS_ROOMS).flatMap { room -> COMMONS_ROOM_KEYS.map { "SPOOL_COMMONS_${room}_$it" } } +
        (2..MAX_TURN_SERVERS).flatMap { server -> TURN_SERVER_KEYS.map { "SPOOL_TURN_${server}_$it" } }

/**
 * The most commons rooms one spool runs (spec §7.4): the unnumbered `SPOOL_COMMONS_*` room and
 * `SPOOL_COMMONS_2_*` through this. A fixed cap keeps [KNOWN_VARS] a finite list, so a typo in a
 * room's variable is still caught and a tool that renders a spool's environment can know every name
 * (ADR 2026-10.5cwn).
 */
internal const val MAX_COMMONS_ROOMS = 8

/**
 * The most TURN servers one spool signs for (SPOOL_PROTOCOL.md S-7.6-48): the unnumbered
 * `SPOOL_TURN_*` server and `SPOOL_TURN_2_*` through this. Fixed for [MAX_COMMONS_ROOMS]' reason, and
 * small because a client measures every one of them on every network it joins.
 */
internal const val MAX_TURN_SERVERS = 4

/**
 * `SPOOL_COMMONS_RATE_TOTAL`'s floor, the spec's suggested spool-wide budget (§12.2). The default is
 * this or the largest room's own budget, whichever is higher, so a one-room spool keeps every push
 * its `SPOOL_COMMONS_RATE_PUSHES` allowed before rooms existed.
 */
private const val COMMONS_RATE_TOTAL_FLOOR = 40

/**
 * A `list` reply carries every live id and every tombstone in one record, so a scope's frame cap
 * and `SPOOL_MAX_RECORD` are coupled. 32 id bytes plus two of CBOR header, and [RECORD_ENVELOPE]
 * for the record around them.
 */
private const val ID_RECORD_BYTES = 34

/**
 * The most casts a second one connection may be allowed. An honest walkie sends about two; past a
 * hundred the per-connection bucket bounds nothing the record bucket does not already.
 */
private const val MAX_CAST_RATE = 100

/** A coturn REST secret is an HMAC key; 32 characters is the floor `openssl rand -base64 24` clears. */
private const val MIN_TURN_SECRET = 32

/**
 * coturn checks a REST credential's expiry only when a session first authenticates — its Allocate —
 * and refreshes reuse the cached key (4.18.0, `check_stun_auth`). So the lifetime is the window in
 * which a credential can *start* relaying, not a cap on a call: a minute is enough for ICE to
 * allocate, and an hour is already far more than any honest client needs.
 */
private const val MIN_TURN_TTL_S = 60L
private const val MAX_TURN_TTL_S = 3_600L
private const val MAX_TURN_RATE = 60

/** Four times [MAX_TURN_RATE]: one holder's budget may be a whole address's at the highest per-connection rate. */
private const val MAX_TICKET_TURN_RATE = 4 * MAX_TURN_RATE

/** One `turn:`/`turns:` URI (RFC 7065): no whitespace, and no comma, which separates the list. */
private val TURN_URL = Regex("^turns?:[^\\s,]+$")

/** One `stun:`/`stuns:` URI (RFC 7064), on [TURN_URL]'s terms. */
private val STUN_URL = Regex("^stuns?:[^\\s,]+$")

/** More STUN servers than this only multiplies a client's gathering; two would already be generous. */
private const val MAX_STUN_URLS = 4

/** A ticket's `aud`: a lowercase host name, which is what an operator would name a spool by. */
private val TICKET_AUDIENCE = Regex("^[a-z0-9.-]{1,253}$")

/** The CBOR record around a payload — the fields beside the blob in a `push`, or the ids in a `list`. */
private const val RECORD_ENVELOPE = 512

/** `SPOOL_STATUS_MS` floor: a sub-second cadence would flood the log the status line exists to make readable. */
private const val MIN_STATUS_MS = 1_000L

/**
 * How much of a rejected `SPOOL_COMMONS_ID` the error echoes: the width [shortHex] leaves in a log
 * line, and as unmatchable.
 */
private const val ECHOED_ID_CHARS = 8

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
            val secret = ByteArray(Commons.SECRET_BYTES).also(SecureRandom()::nextBytes)
            exitProcess(commonsInvite(args.drop(1), secret, ::println, System.err::println))
        }

        "check" -> {
            exitProcess(checkConfig(System.getenv()))
        }

        "ticket-keygen" -> {
            ticketKeygen(::println)
        }

        "ticket-mint" -> {
            exitProcess(ticketMint(args.drop(1), System.currentTimeMillis(), ::println, System.err::println))
        }

        else -> {
            System.err.println("unknown command \"$command\"; usage: knit-spool [commons-invite|check|ticket-keygen|ticket-mint]")
            exitProcess(2)
        }
    }
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
 * boot only when a persistent store already holds `SPOOL_MAX_SCOPES` scopes, none of them empty,
 * which needs the store open to see. This checks configuration, not store state.
 *
 * The resolved config goes to stdout and everything else to stderr, so a caller can parse one
 * without filtering the other.
 */
internal fun checkConfig(environment: Map<String, String>): Int {
    // What boot will run, `SPOOL_RELOAD_FILE` included; reading the file leaves nothing behind.
    val configured =
        try {
            bootEnvironment(environment) { System.err.println("warning: $it") }
        } catch (e: IOException) {
            System.err.println("invalid configuration: cannot read SPOOL_RELOAD_FILE (${environment["SPOOL_RELOAD_FILE"]}): ${e.message}")
            return 1
        }
    unknownVars(configured).forEach { System.err.println("warning: unrecognized environment variable $it — typo?") }
    val config =
        try {
            configFromEnv(configured::get)
        } catch (e: IllegalArgumentException) {
            System.err.println("invalid configuration: ${e.message}")
            return 1
        }
    val dataDir = configured["SPOOL_DATA_DIR"]?.takeIf { it.isNotEmpty() }
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
 * SIGUSR1 toggles draining: `docker exec knit-spool kill -USR1 1` closes the door to new
 * connections and leaves the live ones alone, and sending it again re-opens. Not
 * `docker kill --signal=USR1` — that marks the container manually stopped whatever signal it
 * carries, and `restart: unless-stopped` then skips it on the next reboot.
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
 *
 * Boot reads the same file over the same environment ([bootEnvironment]), so a restart comes back
 * with what the last reload installed rather than undoing it (ADR 2026-10.hnqs). The commons rooms
 * are built against the limits the running store was built from, never against a boot-only limit
 * the file names and the reload ignores.
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
            // Whatever went wrong reading it, the running configuration stands: a reload must
            // never take the daemon down.
            @Suppress("TooGenericExceptionCaught")
            val overrides =
                try {
                    readEnvFile(Path.of(path))
                } catch (e: Exception) {
                    log.error("SIGHUP: cannot read {}: {} — configuration unchanged", path, e.message)
                    return@SignalHandler
                }
            val candidate =
                try {
                    configFromEnv(roomLimits = server.hardLimits) { name -> overrides[name] ?: environment[name] }
                } catch (e: IllegalArgumentException) {
                    log.error("SIGHUP: invalid configuration: {} — configuration unchanged", e.message)
                    return@SignalHandler
                }

            // A reload that races shutdown meets a closed store. Nothing is installed before the
            // store hop that would throw, so the running configuration still stands.
            @Suppress("TooGenericExceptionCaught")
            val ignored =
                try {
                    server.reload(candidate)
                } catch (e: Exception) {
                    log.error("SIGHUP: reload failed: {} — configuration unchanged", e.message)
                    return@SignalHandler
                }
            ignored.forEach { line -> log.warn("SIGHUP: {} — ignored", line) }
            log.info("reloaded configuration from {}: {}", path, server.reloadable.entries.joinToString(" ") { (k, v) -> "$k=$v" })
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

/**
 * The environment with `SPOOL_RELOAD_FILE` layered over it, the overlay a `SIGHUP` installs: what
 * boot and `check` configure from (ADR 2026-10.hnqs). Without it a restart undid every reload. The
 * file's lines fell back to the environment, so a room rotated through the file came back as the old
 * room, with the removed member's invite working again.
 *
 * A file that does not exist yet means no overrides, which is how a spool runs before its first
 * reload. Any other failure to read it throws, so boot refuses to start: running on the environment
 * alone would quietly undo whatever the file holds. The file's own path comes from the environment
 * only, as the `SIGHUP` handler's does, so a `SPOOL_RELOAD_FILE` line inside it is dropped with a
 * [warn].
 */
internal fun bootEnvironment(
    environment: Map<String, String>,
    warn: (String) -> Unit,
): Map<String, String> {
    val path = environment["SPOOL_RELOAD_FILE"]?.takeIf { it.isNotEmpty() } ?: return environment
    val overrides =
        try {
            readEnvFile(Path.of(path))
        } catch (_: NoSuchFileException) {
            return environment
        }
    if ("SPOOL_RELOAD_FILE" in overrides) warn("SPOOL_RELOAD_FILE inside $path is ignored: the file's path comes from the environment")
    return environment + (overrides - "SPOOL_RELOAD_FILE")
}

private fun serve() {
    val environment = System.getenv()
    val configured =
        try {
            bootEnvironment(environment) { log.warn(it) }
        } catch (e: IOException) {
            log.error("cannot read SPOOL_RELOAD_FILE ({}): {}", environment["SPOOL_RELOAD_FILE"], e.message)
            exitProcess(1)
        }
    // Names only: a value here may be a token, and SECURITY.md makes a token in this log a vulnerability.
    val fromFile = configured.filter { (name, value) -> environment[name] != value }.keys.sorted()
    if (fromFile.isNotEmpty()) log.info("SPOOL_RELOAD_FILE sets {} over the environment", fromFile.joinToString(" "))
    unknownVars(configured).forEach { log.warn("unrecognized environment variable {} — typo?", it) }
    val config =
        try {
            configFromEnv(configured::get)
        } catch (e: IllegalArgumentException) {
            log.error("invalid configuration: {}", e.message)
            exitProcess(1)
        }
    val dataDir = configured["SPOOL_DATA_DIR"]?.takeIf { it.isNotEmpty() }

    // Process boundary: nothing the store can throw at open is recoverable here.
    @Suppress("TooGenericExceptionCaught")
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

/** The configuration [env] describes, or an [IllegalArgumentException] naming what is wrong with it. */
internal fun configFromEnv(env: (String) -> String?): SpoolServer.Config = configFromEnv(roomLimits = null, env)

/**
 * [configFromEnv] for a reload: [roomLimits] is the running spool's hard limits, and its rooms are built
 * and their footprints checked against those, the limits the store was built from. Without them, a
 * reload file that also names a boot-only limit would build the rooms against a value the reload then
 * ignores.
 */
internal fun configFromEnv(
    roomLimits: HardLimits?,
    env: (String) -> String?,
): SpoolServer.Config {
    val maxBlob = intVar(env, "SPOOL_MAX_BLOB", default = 65_536, min = 1)
    val maxRecord = intVar(env, "SPOOL_MAX_RECORD", default = 131_072, min = 1)
    require(maxBlob + RECORD_ENVELOPE <= maxRecord) {
        "SPOOL_MAX_BLOB ($maxBlob) + $RECORD_ENVELOPE bytes of CBOR envelope must fit SPOOL_MAX_RECORD ($maxRecord)"
    }
    // 0 is the off switch; anything else is a cadence.
    val statusMs = longVar(env, "SPOOL_STATUS_MS", default = 300_000L, min = 0L)
    require(statusMs == 0L || statusMs >= MIN_STATUS_MS) {
        "SPOOL_STATUS_MS must be 0 (off) or >= $MIN_STATUS_MS, got $statusMs"
    }
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
    // Casts (experimental, SPOOL_PROTOCOL.md §7.7). A rate of 0 is the off switch and the default.
    val maxCast = intVar(env, "SPOOL_MAX_CAST", default = 2_048, min = 1)
    require(maxCast + RECORD_ENVELOPE <= maxRecord) {
        "SPOOL_MAX_CAST ($maxCast) + $RECORD_ENVELOPE bytes of CBOR envelope must fit SPOOL_MAX_RECORD ($maxRecord)"
    }
    val turn = turnFromEnv(env)
    val ticket = ticketFromEnv(env)
    val rooms = roomsFromEnv(env, roomLimits ?: hardLimits, maxRecord, maxBytes)
    // A holder's TURN budget on a spool that issues no TURN would be a limit on nothing.
    require(turn != null || env("SPOOL_TICKET_TURN_RATE") == null) {
        "SPOOL_TICKET_TURN_RATE needs SPOOL_TURN_SECRET; set it, or unset the rate"
    }
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
        // Announced in hello and honoured by clients (spec §7.5). The spool itself enforces nothing:
        // it holds ciphertext and has nothing to screen.
        requireModeration = boolVar(env, "SPOOL_REQUIRE_MODERATION", default = false),
        sourceUrl = sourceUrlFromEnv(env),
        rooms = rooms,
        commonsRateTotal = commonsRateTotalFromEnv(env, rooms),
        turn = turn,
        stunUrls = stunFromEnv(env),
        ticket = ticket,
        rateCasts = intVar(env, "SPOOL_RATE_CASTS", default = 0, min = 0, max = MAX_CAST_RATE),
        maxCast = maxCast,
        rateCastsTotal = intVar(env, "SPOOL_RATE_CASTS_TOTAL", default = 200, min = 1),
    )
}

/**
 * Entitlement-ticket checking (experimental, SPOOL_PROTOCOL.md §7.6), or null when
 * `SPOOL_TICKET_KEY` is unset. The key is the issuer's raw Ed25519 public key in unpadded base64url,
 * as `knit-spool ticket-keygen` prints it; it is decoded and tried as a verifier here, so a key the
 * JDK cannot use stops the spool at boot rather than refusing the first ticket holder.
 *
 * An audience, an alias, gates or an attach budget without a key is refused rather than ignored,
 * for the TURN settings' reason. None is a secret, but the key is still never echoed: a refusal that
 * printed it would invite pasting the private half next time to "check".
 */
internal fun ticketFromEnv(env: (String) -> String?): SpoolServer.TicketConfig? {
    val rawKey = env("SPOOL_TICKET_KEY")?.takeIf { it.isNotEmpty() }
    val audience = env("SPOOL_TICKET_AUDIENCE")?.takeIf { it.isNotEmpty() }
    // Read before the early return, so a gate list with no key is refused rather than never looked at.
    val rawGates = env("SPOOL_TICKET_GATES")?.takeIf { it.isNotEmpty() }
    val rawAttach = env("SPOOL_TICKET_ATTACH_BYTES")
    val rawNext = env("SPOOL_TICKET_KEY_NEXT")?.takeIf { it.isNotEmpty() }
    val rawTurnRate = env("SPOOL_TICKET_TURN_RATE")
    val alias = env("SPOOL_TICKET_AUDIENCE_ALIAS")?.takeIf { it.isNotEmpty() }
    if (rawKey == null) {
        require(audience == null) { "SPOOL_TICKET_AUDIENCE needs SPOOL_TICKET_KEY; set it, or unset the audience" }
        require(rawGates == null) { "SPOOL_TICKET_GATES needs SPOOL_TICKET_KEY; set it, or unset the gates" }
        require(rawAttach == null) { "SPOOL_TICKET_ATTACH_BYTES needs SPOOL_TICKET_KEY; set it, or unset the budget" }
        require(rawNext == null) {
            "SPOOL_TICKET_KEY_NEXT is set but SPOOL_TICKET_KEY is not: set both during a rotation, then promote NEXT into KEY"
        }
        require(rawTurnRate == null) { "SPOOL_TICKET_TURN_RATE needs SPOOL_TICKET_KEY; set it, or unset the rate" }
        require(alias == null) {
            "SPOOL_TICKET_AUDIENCE_ALIAS needs SPOOL_TICKET_KEY and SPOOL_TICKET_AUDIENCE; set them, or unset the alias"
        }
        return null
    }
    requireNotNull(audience) { "SPOOL_TICKET_KEY is set but SPOOL_TICKET_AUDIENCE is not: tickets must name the spool they are for" }
    require(TICKET_AUDIENCE.matches(audience)) { "SPOOL_TICKET_AUDIENCE must be a lowercase host name, got \"$audience\"" }
    if (alias != null) {
        require(TICKET_AUDIENCE.matches(alias)) { "SPOOL_TICKET_AUDIENCE_ALIAS must be a lowercase host name, got \"$alias\"" }
        require(alias != audience) { "SPOOL_TICKET_AUDIENCE_ALIAS must differ from SPOOL_TICKET_AUDIENCE" }
    }
    val raw = ticketKeyBytes("SPOOL_TICKET_KEY", rawKey)
    val next = rawNext?.let { ticketKeyBytes("SPOOL_TICKET_KEY_NEXT", it) }
    require(next == null || !next.contentEquals(raw)) { "SPOOL_TICKET_KEY_NEXT must differ from SPOOL_TICKET_KEY" }
    return SpoolServer.TicketConfig(
        key = Tickets.publicKey(raw),
        kid = Tickets.kid(raw),
        audience = audience,
        audienceAlias = alias,
        gates = ticketGates(rawGates),
        attachBytes = longVar(env, "SPOOL_TICKET_ATTACH_BYTES", default = SpoolServer.TicketConfig.DEFAULT_ATTACH_BYTES, min = 0),
        next = next?.let { SpoolServer.TicketKey(Tickets.publicKey(it), Tickets.kid(it)) },
        turnRatePer10Min =
            intVar(
                env,
                "SPOOL_TICKET_TURN_RATE",
                default = SpoolServer.TicketConfig.DEFAULT_TURN_RATE,
                min = 0,
                max = MAX_TICKET_TURN_RATE,
            ),
    )
}

/**
 * [raw] decoded and tried as an Ed25519 verifier, so a key the JDK cannot use stops the spool at boot
 * rather than refusing the first ticket holder. The refusal names [name] and never echoes the value.
 */
private fun ticketKeyBytes(
    name: String,
    raw: String,
): ByteArray {
    val bytes =
        requireNotNull(runCatching { Base64.getUrlDecoder().decode(raw) }.getOrNull()) {
            "$name must be unpadded base64url (${raw.length} characters given)"
        }
    try {
        Tickets.publicKey(bytes)
    } catch (e: GeneralSecurityException) {
        throw IllegalArgumentException("$name is not a usable Ed25519 public key (${bytes.size} bytes decoded)", e)
    }
    return bytes
}

/**
 * `SPOOL_TICKET_GATES` as [Tickets] flag bits (ADR 2026-10.h6pt): unset is `turn,cast`, what a key
 * gated before the list existed. `create` is refused by name, so no operator believes scope creation
 * is gated: ADR 2026-10.x9gq keeps it open.
 */
private fun ticketGates(raw: String?): Long {
    if (raw == null) return Tickets.LEGACY_GATES
    return raw.split(',').map(String::trim).fold(0L) { gates, word ->
        require(word != "create") { "SPOOL_TICKET_GATES: \"create\" is not supported yet; scope creation is not ticket-gated" }
        val bit = SpoolServer.TicketConfig.GATE_WORDS[word]
        requireNotNull(bit) { "SPOOL_TICKET_GATES entries must be turn, files or cast, got \"$word\"" }
        gates or bit
    }
}

/**
 * The STUN URIs `hello` advertises (experimental, SPOOL_PROTOCOL.md §7.6), or none when
 * `SPOOL_STUN_URLS` is unset. They carry no credential and need no secret, so unlike the TURN
 * settings they stand alone.
 */
internal fun stunFromEnv(env: (String) -> String?): List<String> {
    val raw = env("SPOOL_STUN_URLS")?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val urls = raw.split(',').map(String::trim)
    require(urls.size <= MAX_STUN_URLS) { "SPOOL_STUN_URLS lists ${urls.size} URIs; at most $MAX_STUN_URLS" }
    urls.forEach { url ->
        require(STUN_URL.matches(url)) { "SPOOL_STUN_URLS entries must be stun: or stuns: URIs, got \"$url\"" }
    }
    return urls
}

/**
 * TURN credential issuing (experimental, SPOOL_PROTOCOL.md §7.6), or null when `SPOOL_TURN_SECRET` is
 * unset — off by default, and a spool without it advertises nothing and answers `turnreq` with
 * `err malformed`.
 *
 * Server 1 is the unnumbered `SPOOL_TURN_SECRET` and `SPOOL_TURN_URLS`, and further servers are
 * `SPOOL_TURN_2_*` up to [MAX_TURN_SERVERS], numbered with no gap (S-7.6-48, ADR 2026-10.u5f4). Each
 * has its own secret and URLs; the lifetime and the rates are shared. Server 1 is what a client that
 * names no host gets, so every spool that ran with one server runs exactly as before.
 *
 * With two or more servers, a server is known by its host: its URLs must name one, no two servers may
 * share it, and each needs a UDP URL, since that is what a client measures and names in `turnreq`.
 * One server keeps every URL list it was accepted with before.
 *
 * The URLs and lifetime without a secret are refused rather than ignored: they mean an operator
 * believes the spool is handing out credentials that it is not. Neither a secret nor any part of one
 * is ever echoed, here or anywhere.
 */
internal fun turnFromEnv(env: (String) -> String?): SpoolServer.TurnConfig? {
    // Each server's secret and URLs, server 1 first. Empty is unset, the SPOOL_TOKEN idiom: compose
    // passes a blank `.env` line as "".
    val raw =
        (1..MAX_TURN_SERVERS).map { number ->
            env(turnVar(number, "SECRET"))?.takeIf(String::isNotEmpty) to env(turnVar(number, "URLS"))?.takeIf(String::isNotEmpty)
        }
    for (number in 2..MAX_TURN_SERVERS) {
        val (secret, urls) = raw[number - 1]
        if (secret == null && urls == null) continue
        val (before, beforeUrls) = raw[number - 2]
        require(before != null || beforeUrls != null) {
            "${turnVar(number, if (secret != null) "SECRET" else "URLS")} is set but neither ${turnVar(number - 1, "SECRET")} " +
                "nor ${turnVar(number - 1, "URLS")} is: number TURN servers from 1 with no gap"
        }
    }
    val (firstSecret, firstUrls) = raw.first()
    if (firstSecret == null) {
        require(firstUrls == null && env("SPOOL_TURN_TTL_S") == null && env("SPOOL_TURN_RATE") == null) {
            "SPOOL_TURN_URLS, SPOOL_TURN_TTL_S and SPOOL_TURN_RATE need SPOOL_TURN_SECRET; set it, or unset them"
        }
        return null
    }
    val servers = raw.withIndex().mapNotNull { (index, keys) -> turnServer(index + 1, keys.first, keys.second) }
    if (servers.size > 1) checkTurnHosts(servers)
    return SpoolServer.TurnConfig(
        servers = servers,
        ttlS = longVar(env, "SPOOL_TURN_TTL_S", default = 300L, min = MIN_TURN_TTL_S, max = MAX_TURN_TTL_S),
        ratePer10Min = intVar(env, "SPOOL_TURN_RATE", default = 4, min = 1, max = MAX_TURN_RATE),
    )
}

/** `SPOOL_TURN_<key>` for server 1, `SPOOL_TURN_<number>_<key>` for the rest, as [roomVar] names rooms. */
private fun turnVar(
    number: Int,
    key: String,
): String = if (number == 1) "SPOOL_TURN_$key" else "SPOOL_TURN_${number}_$key"

/** One server's secret and URLs, checked alone, or null when neither is set. */
private fun turnServer(
    number: Int,
    secret: String?,
    rawUrls: String?,
): SpoolServer.TurnServer? {
    val (secretVar, urlsVar) = turnVar(number, "SECRET") to turnVar(number, "URLS")
    if (secret == null) {
        require(rawUrls == null) { "$urlsVar needs $secretVar; set it, or unset the URLs" }
        return null
    }
    require(secret.length >= MIN_TURN_SECRET) {
        "$secretVar must be at least $MIN_TURN_SECRET characters (openssl rand -base64 48)"
    }
    requireNotNull(rawUrls) { "$secretVar is set but $urlsVar is not: clients need somewhere to use it" }
    val urls = rawUrls.split(',').map(String::trim)
    urls.forEach { url ->
        require(TURN_URL.matches(url)) { "$urlsVar entries must be turn: or turns: URIs, got \"$url\"" }
    }
    return SpoolServer.TurnServer(number = number, secret = secret, urls = urls)
}

/**
 * What two or more servers need on top of [turnServer]'s checks: one host each, none shared, and a UDP
 * URL each. A client tells servers apart by host and measures each at its UDP URL's host and port, and
 * a credential's server is read from its URLs, so a server spread over two hosts has no single identity.
 */
private fun checkTurnHosts(servers: List<SpoolServer.TurnServer>) {
    val hosts =
        servers.map { server ->
            val urlsVar = turnVar(server.number, "URLS")
            val named =
                server.urls
                    .map { url ->
                        requireNotNull(TurnUris.host(url)) {
                            "$urlsVar entry \"$url\" names no host; with several TURN servers, each is known by its host"
                        }
                    }.distinct()
            require(named.size == 1) {
                "$urlsVar names ${named.size} hosts (${named.joinToString(", ")}); " +
                    "with several TURN servers, each server's URLs must name one host"
            }
            requireNotNull(server.entry) {
                "$urlsVar has no turn: URL over UDP; with several TURN servers, clients measure each at its UDP URL"
            }
            named.single()
        }
    for ((index, host) in hosts.withIndex()) {
        val clash = hosts.take(index).indexOf(host).takeIf { it >= 0 } ?: continue
        throw IllegalArgumentException(
            "${turnVar(servers[index].number, "URLS")} names $host, as ${turnVar(servers[clash].number, "URLS")} does; " +
                "each TURN server must be on a host of its own",
        )
    }
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
 * The commons rooms (spec §7.4), first to last, or none when `SPOOL_COMMONS_ID` is unset — the whole
 * feature is off by default, and a spool without one behaves exactly as before.
 *
 * The first room is the unnumbered `SPOOL_COMMONS_*`, and further ones are `SPOOL_COMMONS_2_*` up to
 * [MAX_COMMONS_ROOMS], in number order, gaps allowed. The first is the one `hello`'s `commons` repeats
 * for clients that predate `rooms`, which is why a numbered room needs it: a spool advertising its
 * second room as its only `commons` would hand old clients the wrong room's bounds.
 *
 * Checked as a set as well as one by one: every room is pinned against the watermark, so their
 * footprints together must fit `SPOOL_MAX_BYTES`, and no two may share an id or a tag (S-7.4-15).
 * Rooms whose `ttlMs` differs from the first's are legal and warned: a client that predates `rooms`
 * compares a room's echoed `ttlMs` with `commons`' and would take such a room for a rotated one.
 */
internal fun roomsFromEnv(
    env: (String) -> String?,
    hardLimits: HardLimits,
    maxRecord: Int,
    maxBytes: Long,
): List<SpoolServer.CommonsConfig> {
    val first = roomFromEnv(env, 1, hardLimits, maxRecord)
    val numbered =
        (2..MAX_COMMONS_ROOMS).mapNotNull { number ->
            roomFromEnv(env, number, hardLimits, maxRecord)?.also {
                requireNotNull(first) { "${roomVar(number, "ID")} needs SPOOL_COMMONS_ID: the first room is the one older clients see" }
            }
        }
    val rooms = listOfNotNull(first) + numbered
    for ((index, room) in rooms.withIndex()) {
        val clash = rooms.take(index).firstOrNull { it.tag.contentEquals(room.tag) } ?: continue
        val (mine, theirs) = roomVar(room.number, "ID") to roomVar(clash.number, "ID")
        throw IllegalArgumentException(
            if (clash.scopeId.contentEquals(room.scopeId)) {
                "$mine repeats $theirs"
            } else {
                "$mine and $theirs share a tag; mint a new invite for one of them"
            },
        )
    }
    // Every room is pinned against the watermark, so together they must leave it something to shed.
    if (maxBytes > 0 && rooms.isNotEmpty()) {
        val footprint = rooms.sumOf { it.footprint(hardLimits) }
        require(footprint <= maxBytes) {
            val what = if (rooms.size == 1) "the commons can hold" else "the ${rooms.size} commons rooms can hold"
            "$what $footprint bytes (${rooms.joinToString(" + ") { it.footprintTerms(hardLimits) }}), over SPOOL_MAX_BYTES " +
                "($maxBytes); every room is pinned against the watermark, so they must fit"
        }
    }
    val firstTtl = rooms.firstOrNull()?.bounds?.ttlMs
    rooms.filter { it.bounds.ttlMs != firstTtl }.forEach {
        log.warn(
            "{} ({}) differs from SPOOL_COMMONS_TTL_MS ({}): a client that predates `rooms` will take that room for a rotated one",
            roomVar(it.number, "TTL_MS"),
            it.bounds.ttlMs,
            firstTtl,
        )
    }
    return rooms
}

/** `SPOOL_COMMONS_<key>` for the first room, `SPOOL_COMMONS_<number>_<key>` for the rest. */
private fun roomVar(
    number: Int,
    key: String,
): String = if (number == 1) "SPOOL_COMMONS_$key" else "SPOOL_COMMONS_${number}_$key"

/**
 * One room's variables, or null when its id is unset.
 *
 * The id is `SHA-256("knit/spool/v1/commons" ‖ secret)`, minted by `knit-spool commons-invite`.
 * Only the hash is configured here: the secret goes to members, so the spool holds a room it cannot
 * read. Rotating a room means minting a new invite and putting its id in `SPOOL_RELOAD_FILE` before a
 * `SIGHUP`, which closes only that room's members (ADR 2026-10.hs75). The old scope is no longer
 * pinned and ages out on its own TTL or under the watermark.
 *
 * A numbered room's other variables without its id are refused, for the ticket settings' reason; the
 * first room's are ignored as they always were, so no configuration that booted before rooms stops.
 */
private fun roomFromEnv(
    env: (String) -> String?,
    number: Int,
    hardLimits: HardLimits,
    maxRecord: Int,
): SpoolServer.CommonsConfig? {
    fun name(key: String) = roomVar(number, key)
    val raw = env(name("ID"))?.takeIf { it.isNotEmpty() }
    if (raw == null) {
        if (number > 1) {
            // Empty is unset, the SPOOL_TOKEN idiom: compose passes a blank `.env` line as "".
            COMMONS_ROOM_KEYS.drop(1).firstOrNull { !env(name(it)).isNullOrEmpty() }?.let {
                throw IllegalArgumentException("${name(it)} needs ${name("ID")}; set it, or unset the room's other settings")
            }
        }
        return null
    }
    // Never echoed in full: Main.serve logs this message at ERROR, and one mistyped character in
    // a real id would put a near-real scope id in the log the rest of this daemon keeps out.
    require(HEX_64.matches(raw)) {
        "${name("ID")} must be 64 hex characters (a 32-byte scope id), got ${raw.length} " +
            "characters starting \"${raw.take(ECHOED_ID_CHARS)}\""
    }
    val maxFrames = intVar(env, name("MAX_FRAMES"), default = 500, min = 1)
    val ttlMs = longVar(env, name("TTL_MS"), default = 86_400_000L, min = 1L)
    val roomMaxBlob = intVar(env, name("MAX_BLOB"), default = hardLimits.maxBlob, min = 1)

    // Inside the hard caps, so the store's subscribe-time clamp is a no-op and the bounds a client
    // is told about in `digest` are the bounds the operator configured.
    require(maxFrames <= hardLimits.maxFramesCap) {
        "${name("MAX_FRAMES")} ($maxFrames) must not exceed SPOOL_MAX_FRAMES (${hardLimits.maxFramesCap})"
    }
    require(ttlMs <= hardLimits.maxTtlMs) {
        "${name("TTL_MS")} ($ttlMs) must not exceed SPOOL_MAX_TTL_MS (${hardLimits.maxTtlMs})"
    }
    require(roomMaxBlob <= hardLimits.maxBlob) {
        "${name("MAX_BLOB")} ($roomMaxBlob) must not exceed SPOOL_MAX_BLOB (${hardLimits.maxBlob})"
    }

    // A `list` for the room must fit one record, or the transport kills it (1009) and the room
    // can never be caught up on. Tombstones ride the same reply, bounded by ScopeStore.tombstoneCap.
    val listBytes = (maxFrames.toLong() + maxOf(2L * maxFrames, 1024L)) * ID_RECORD_BYTES + RECORD_ENVELOPE
    require(listBytes <= maxRecord) {
        "${name("MAX_FRAMES")} ($maxFrames) needs a $listBytes-byte list reply, over " +
            "SPOOL_MAX_RECORD ($maxRecord); lower it or raise SPOOL_MAX_RECORD"
    }

    return SpoolServer.CommonsConfig(
        scopeId = unhex(raw),
        name = env(name("NAME"))?.takeIf { it.isNotEmpty() },
        bounds = ScopeBounds(maxFrames = maxFrames, ttlMs = ttlMs, maxBlob = roomMaxBlob),
        attach = boolVar(env, name("ATTACH"), default = false),
        ratePushes = intVar(env, name("RATE_PUSHES"), default = 20, min = 1),
        number = number,
    )
}

/**
 * `SPOOL_COMMONS_RATE_TOTAL`: pushes per second into every room together (S-7.4-4), beside each
 * room's own budget. Refused without a room, like any setting for a feature that is off.
 *
 * Empty is unset, as a room's id is: a reload file removes the last room the environment set with
 * blank lines, and the environment's rate has to go with it.
 */
private fun commonsRateTotalFromEnv(
    env: (String) -> String?,
    rooms: List<SpoolServer.CommonsConfig>,
): Int {
    val default = maxOf(COMMONS_RATE_TOTAL_FLOOR, rooms.maxOfOrNull { it.ratePushes } ?: 0)
    val set: (String) -> String? = { name -> env(name)?.takeIf { it.isNotEmpty() } }
    require(rooms.isNotEmpty() || set("SPOOL_COMMONS_RATE_TOTAL") == null) {
        "SPOOL_COMMONS_RATE_TOTAL needs SPOOL_COMMONS_ID; set it, or unset the rate"
    }
    return intVar(set, "SPOOL_COMMONS_RATE_TOTAL", default = default, min = 1)
}

private fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(radix = 16).toByte() }

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
    max: Long = Long.MAX_VALUE,
): Long {
    val raw = env(name) ?: return default
    val value = requireNotNull(raw.toLongOrNull()) { "$name must be an integer, got \"$raw\"" }
    require(value >= min) { "$name must be >= $min, got $value" }
    require(value <= max) { "$name must be <= $max, got $value" }
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
