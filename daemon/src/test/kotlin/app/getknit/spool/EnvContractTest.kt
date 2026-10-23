// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The environment contract moves only with the development version (ADR 2026-10.8ymn).
 *
 * Something that renders a spool's environment reads the image's version label to decide which
 * variables the daemon inside will understand. A daemon handed one it does not know logs
 * "unrecognized environment variable — typo?" and boots anyway, so a contract that moved under an
 * unchanged `-dev.<n>` fails silently, on someone else's machine. This pins a fingerprint of the
 * contract beside the version that introduced it, and fails when one moves without the other.
 *
 * The fingerprint covers every [KNOWN_VARS] name, which half of the boot log each field sits in
 * (`bootOnly()` is restart-only, `reloadable()` is what `SIGHUP` replaces), and every resolved
 * default, with tickets and TURN switched on so their defaults are in it too.
 *
 * When this fails: bump `-dev.<n>` in `build.gradle.kts`, then set [PINNED] to the new version and
 * the fingerprint the failure prints. Never change the fingerprint alone — that is the one edit
 * this check cannot see through, and it is the bug the check exists to prevent.
 */
class EnvContractTest {
    @Test
    fun theContractMovesOnlyWithTheDevelopmentVersion() {
        val actual = fingerprint(contract())
        assertEquals(
            PINNED,
            TreeVersion.literal to actual,
            "the environment contract or the development version moved without the other. Contract now:\n" +
                contract().joinToString("\n") +
                "\nIf the contract moved, bump -dev.<n> in build.gradle.kts and pin (\"<new version>\", \"$actual\"). " +
                "If only the version moved (a release reset), pin (\"${TreeVersion.literal}\", \"$actual\").",
        )
    }

    /** One line per fact, sorted, so the failure message doubles as a readable diff. */
    private fun contract(): List<String> {
        val config =
            configFromEnv(
                mapOf(
                    "SPOOL_TICKET_KEY" to TICKET_KEY,
                    "SPOOL_TICKET_AUDIENCE" to "spool.example",
                    "SPOOL_TURN_SECRET" to "0123456789abcdef0123456789abcdef",
                    "SPOOL_TURN_URLS" to "turn:spool.example:3478?transport=udp",
                )::get,
            )
        return KNOWN_VARS.sorted().map { "var $it" } +
            config.bootOnly().map { (k, v) -> "boot $k=$v" } +
            config.reloadable().map { (k, v) -> "reload $k=$v" }
    }

    private fun fingerprint(lines: List<String>): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(lines.joinToString("\n").toByteArray())
            .toHexString()
            .take(16)

    private companion object {
        /**
         * The development version and the contract's fingerprint at it. dev.2 adds
         * `SPOOL_TICKET_AUDIENCE_ALIAS` and its `ticketAudAlias` report field (ADR 2026-10.zprp).
         */
        val PINNED = "0.5.0-dev.2" to "b62a743417092bd0"

        /** RFC 8032 §7.1 TEST 1's public key, unpadded base64url, as `ticket-keygen` prints one. */
        val TICKET_KEY: String =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToByteArray())
    }
}
