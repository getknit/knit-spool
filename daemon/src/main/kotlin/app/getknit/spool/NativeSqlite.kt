// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Where the native binary's SQLite library is (ADR 2026-11.bxce).
 *
 * The native image keeps `libsqlitejdbc.so` beside the executable instead of extracting one to a
 * temp directory at every boot, and sqlite-jdbc looks only where `org.sqlite.lib.path` points: its
 * next move is a classpath resource the image deliberately does not carry, which strict registration
 * turns into a crash naming the resource rather than the missing file. So the daemon points it at
 * the executable's own directory before the store opens, through `/proc/self/exe`, so that a symlink
 * in PATH (the OpenWrt package's `/usr/bin/knit-spool`) still lands next to the library. An explicit
 * `-Dorg.sqlite.lib.path` wins, and the JVM dist, whose sqlite-jdbc carries the library in its jar,
 * is left alone.
 *
 * @return the library sqlite-jdbc was pointed at, or null when nothing changed.
 */
internal fun pointSqliteAtTheExecutable(
    runtime: String = BuildInfo.runtime,
    executable: Path = Path.of("/proc/self/exe"),
    properties: Properties = System.getProperties(),
): Path? {
    if (runtime != "native" || properties.getProperty("org.sqlite.lib.path") != null) return null
    val dir = runCatching { executable.toRealPath().parent }.getOrNull() ?: return null
    val library = dir.resolve("libsqlitejdbc.so").takeIf { Files.isRegularFile(it) } ?: return null
    properties.setProperty("org.sqlite.lib.path", dir.toString())
    properties.setProperty("org.sqlite.lib.name", library.fileName.toString())
    return library
}
