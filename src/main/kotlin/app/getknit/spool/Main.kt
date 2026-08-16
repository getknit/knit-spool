// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.server.SpoolServer
import app.getknit.spool.store.InMemoryScopeStore

/**
 * knit-spool — a scoped, blinded store-and-forward relay for the Knit mesh messenger's Internet
 * plane. Normative protocol: the Knit repo's docs/SPOOL_PROTOCOL.md. Configuration is environment
 * variables only (container-first; defaults follow the spec §12 table, PoW off until hardening
 * lands):
 *
 *   SPOOL_PORT         listen port                          (default 9470)
 *   SPOOL_TOKEN        bearer token for a private spool     (default unset = public)
 *   SPOOL_POW_BITS     PoW difficulty for unknown scopes    (default 0 = off; spec suggests 20)
 *   SPOOL_MAX_BLOB     max sealed-blob bytes                (default 65536)
 *   SPOOL_MAX_SCOPES   max scopes held                      (default 64)
 *   SPOOL_MAX_FRAMES   per-scope frame cap ceiling          (default 1000)
 *   SPOOL_MAX_TTL_MS   per-scope ttl ceiling                (default 604800000 = 7 d)
 */
fun main() {
    fun env(
        name: String,
        default: Int,
    ): Int = System.getenv(name)?.toIntOrNull() ?: default

    fun env(
        name: String,
        default: Long,
    ): Long = System.getenv(name)?.toLongOrNull() ?: default

    val config =
        SpoolServer.Config(
            port = env("SPOOL_PORT", 9470),
            token = System.getenv("SPOOL_TOKEN")?.takeIf { it.isNotEmpty() },
            powBits = env("SPOOL_POW_BITS", 0),
            maxRecord = 131_072,
            maxPull = 64,
            hardLimits =
                InMemoryScopeStore.HardLimits(
                    maxBlob = env("SPOOL_MAX_BLOB", 65_536),
                    maxFramesCap = env("SPOOL_MAX_FRAMES", 1_000),
                    maxTtlMs = env("SPOOL_MAX_TTL_MS", 604_800_000L),
                    maxScopes = env("SPOOL_MAX_SCOPES", 64),
                ),
        )
    SpoolServer(config).start(wait = true)
}
