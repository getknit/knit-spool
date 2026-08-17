// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.server.SpoolServer
import app.getknit.spool.store.HardLimits
import app.getknit.spool.store.InMemoryScopeStore
import app.getknit.spool.store.SqliteScopeStore
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * knit-spool — a scoped, blinded store-and-forward relay for the Knit mesh messenger's Internet
 * plane. Normative protocol: the Knit repo's docs/SPOOL_PROTOCOL.md. Configuration is environment
 * variables only (container-first); see the README's table. Invalid values fail fast — a spool
 * that silently ran on defaults because of a typo is worse than one that refuses to start.
 */
private val log = LoggerFactory.getLogger("app.getknit.spool.Main")

private val KNOWN_VARS =
    setOf(
        "SPOOL_PORT",
        "SPOOL_TOKEN",
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
        "SPOOL_MAX_CONNS_PER_IP",
        "SPOOL_RATE_RECORDS",
        "SPOOL_RATE_PUSHES",
        "SPOOL_RATE_NEW_SCOPES",
        "SPOOL_LOG_LEVEL",
        "SPOOL_DATA_DIR",
    )

fun main() {
    val environment = System.getenv()
    environment.keys
        .filter { it.startsWith("SPOOL_") && it !in KNOWN_VARS }
        .forEach { log.warn("unrecognized environment variable {} — typo?", it) }
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
    server.start(wait = true)
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
    return SpoolServer.Config(
        port = intVar(env, "SPOOL_PORT", default = 9470, min = 1, max = 65_535),
        token = env("SPOOL_TOKEN")?.takeIf { it.isNotEmpty() },
        powBits = intVar(env, "SPOOL_POW_BITS", default = 0, min = 0, max = 30),
        maxRecord = maxRecord,
        maxPull = intVar(env, "SPOOL_MAX_PULL", default = 64, min = 1),
        maxAget = intVar(env, "SPOOL_MAX_AGET", default = 32, min = 1),
        hardLimits =
            HardLimits(
                maxBlob = maxBlob,
                maxFramesCap = intVar(env, "SPOOL_MAX_FRAMES", default = 1_000, min = 1),
                maxTtlMs = longVar(env, "SPOOL_MAX_TTL_MS", default = 604_800_000L, min = 1L),
                maxScopes = intVar(env, "SPOOL_MAX_SCOPES", default = 64, min = 1),
                // 0 switches attachments off entirely; the server then omits all three limits from
                // HELLO and a conforming client never sends an attachment record (spec §7.3).
                maxAttachBytes = intVar(env, "SPOOL_MAX_ATTACH_BYTES", default = 16_777_216, min = 0),
                maxAChunk = intVar(env, "SPOOL_MAX_A_CHUNK", default = HardLimits.DEFAULT_MAX_A_CHUNK, min = 1),
            ),
        maxBytes = longVar(env, "SPOOL_MAX_BYTES", default = 268_435_456L, min = 0L),
        sweepMs = longVar(env, "SPOOL_SWEEP_MS", default = 60_000L, min = 1_000L),
        statusMs = statusMs,
        trustProxy = boolVar(env, "SPOOL_TRUST_PROXY", default = false),
        maxConnsPerIp = intVar(env, "SPOOL_MAX_CONNS_PER_IP", default = 16, min = 1),
        rateRecords = intVar(env, "SPOOL_RATE_RECORDS", default = 50, min = 1),
        ratePushes = intVar(env, "SPOOL_RATE_PUSHES", default = 10, min = 1),
        rateNewScopesPerMin = intVar(env, "SPOOL_RATE_NEW_SCOPES", default = 6, min = 1),
    )
}

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
