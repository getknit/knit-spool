// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The OpenWrt package under `deploy/openwrt/` speaks the daemon's environment contract and nothing
 * else (ADR 2026-11.zmc3). The init script turns UCI into `SPOOL_*` variables and the example env
 * file is the daemon's reload file, so a name in either that the daemon does not read is a setting
 * that silently never happens: the daemon warns "unrecognized environment variable" once at boot and
 * runs its default. [DeployConfigTest] pins the compose files the same way; this pins the package,
 * which lives below `deploy/` where that test does not look.
 */
class OpenWrtPackageTest {
    private val repoRoot: Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: fail("no settings.gradle.kts at or above ${Path.of("").toAbsolutePath()}")

    private val files = repoRoot.resolve("deploy/openwrt/knit-spool/files")
    private val init = files.resolve("knit-spool.init")
    private val env = files.resolve("env.example")
    private val uci = files.resolve("knit-spool.config")

    /** A variable name; `SPOOL_*` in prose has nothing after the underscore and does not match. */
    private val variable = Regex("""\bSPOOL_[A-Z0-9_]+""")

    private fun text(path: Path): String {
        if (!path.exists()) fail("$path is missing")
        return path.readText()
    }

    private fun names(path: Path): Set<String> = variable.findAll(text(path)).map { it.value }.toSet()

    @Test
    fun everyVariableTheInitScriptSetsIsOneTheDaemonReads() {
        val unknown = names(init) - KNOWN_VARS
        assertTrue(unknown.isEmpty(), "set by the init script but read by nothing, so the setting never happens: $unknown")
    }

    @Test
    fun everyVariableTheExampleEnvFileNamesIsOneTheDaemonReads() {
        val unknown = names(env) - KNOWN_VARS
        assertTrue(unknown.isEmpty(), "named in env.example but read by nothing: $unknown")
    }

    /** The env file is the reload file, or `service knit-spool reload` reloads nothing. */
    @Test
    fun theInitScriptHandsTheEnvFileToTheDaemonAsItsReloadFile() {
        val script = text(init)
        assertTrue("SPOOL_RELOAD_FILE=\"\$env_file\"" in script, "the init script must export the env file as SPOOL_RELOAD_FILE")
        assertTrue("procd_send_signal knit-spool main HUP" in script, "reload must be the daemon's SIGHUP")
        // A UCI change is a restart; a trigger would answer it with a HUP that re-reads the wrong file.
        // Anchored to a call, not a mention: the script's own comment says why there is none.
        assertFalse(
            Regex("""^\s*procd_add_reload_trigger""", RegexOption.MULTILINE).containsMatchIn(script),
            "no UCI reload trigger: a port change needs a restart, not a HUP",
        )
    }

    /** Plaintext stays on loopback unless the operator says otherwise, and nothing runs until they do. */
    @Test
    fun theDefaultsAreLoopbackAndDisabled() {
        val config = text(uci)
        assertTrue(Regex("""^\s*option bind '127\.0\.0\.1'\s*$""", RegexOption.MULTILINE).containsMatchIn(config), config)
        assertTrue(
            Regex("""^\s*option enabled '0'\s*$""", RegexOption.MULTILINE).containsMatchIn(config),
            "the package must install disabled",
        )
        assertTrue("config_get bind main bind 127.0.0.1" in text(init), "the script's fallback must match the UCI default")
    }

    /**
     * With no `data_dir` the store is the heap, and `SPOOL_MAX_BYTES` is the only thing that keeps
     * frames from filling it before the watermark acts. Half the default heap, at most.
     */
    @Test
    fun theInMemoryDefaultCapsPayloadUnderTheHeap() {
        val script = text(init)
        val maxBytes =
            Regex("""SPOOL_MAX_BYTES=(\d+)""")
                .find(script)
                ?.groupValues
                ?.get(1)
                ?.toLong()
                ?: fail("the init script sets no SPOOL_MAX_BYTES default for the in-memory store")
        val heapMb =
            Regex("""config_get heap_mb main heap_mb (\d+)""")
                .find(script)
                ?.groupValues
                ?.get(1)
                ?.toLong()
                ?: fail("the init script has no heap_mb default")
        assertTrue(maxBytes * 2 <= heapMb * 1024 * 1024, "SPOOL_MAX_BYTES ($maxBytes) must leave half of the $heapMb MB heap free")
    }
}
