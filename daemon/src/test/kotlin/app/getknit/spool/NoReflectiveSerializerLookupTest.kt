// SPDX-License-Identifier: AGPL-3.0-or-later
package app.getknit.spool

import app.getknit.spool.server.SpoolServer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readBytes
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * No class in `:daemon` looks a serializer up by `KType` (ADR 2026-11.bxce).
 *
 * `RecordCodec.encode<T>()` and `decode<T>()` are inline, so each call site compiles in the calling
 * module. With the serialization plugin applied there, `T.serializer()` is resolved at compile time;
 * without it the same source compiles to `Reflection.typeOf(...)` and a reflective lookup through
 * `kotlin-reflect`, which a native image cannot satisfy without per-class metadata. Before the plugin
 * was applied here, `SpoolServer.class` held 33 such sites, each a crash in the native build. This
 * reads the compiled classes rather than the source, because the source looks identical either way.
 *
 * The second test keeps `kotlin-reflect` itself off the classpath. Ktor's server core declares it and
 * nothing here calls it; with it present every `typeOf<T>()`, Ktor's own attribute keys included,
 * resolves through kotlin-reflect, which probes the classpath by class name and expects
 * `ClassNotFoundException` back. A native image built with strict registration answers with an
 * error instead, and the first native boot died in the WebSockets plugin's class initializer.
 */
class NoReflectiveSerializerLookupTest {
    @Test
    fun noDaemonClassLooksASerializerUpByType() {
        val classes =
            Path.of(
                SpoolServer::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        assertTrue(Files.isDirectory(classes), "expected an exploded class directory, got $classes")
        val offenders =
            Files.walk(classes).use { paths ->
                paths
                    .asSequence()
                    .filter { it.extension == "class" }
                    .filter { path ->
                        val text = String(path.readBytes(), Charsets.ISO_8859_1)
                        "kotlin/jvm/internal/Reflection" in text && "typeOf" in text
                    }.map { classes.relativize(it).toString() }
                    .sorted()
                    .toList()
            }
        assertTrue(
            offenders.isEmpty(),
            "these classes look a serializer up by KType at run time; the serialization plugin is " +
                "applied to :daemon exactly so that decode<T>() compiles to T.serializer(): $offenders",
        )
    }

    @Test
    fun kotlinReflectIsNotOnTheDaemonClasspath() {
        assertFailsWith<ClassNotFoundException>("kotlin-reflect is back on the classpath; daemon/build.gradle.kts excludes it") {
            Class.forName("kotlin.reflect.jvm.internal.ReflectionFactoryImpl")
        }
    }
}
