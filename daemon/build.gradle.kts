// The reference daemon: WSS server, scope store (in-memory + SQLite), rate limiting, ops.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Not for records of its own -- those live in :protocol -- but because RecordCodec.encode<T>()
    // and decode<T>() are inline and compile here: with the plugin, to T.serializer(); without it,
    // to a reflective lookup by KType that a native image cannot do (ADR 2026-11.bxce).
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    application
    // After `application`: it reads mainClass from it.
    alias(libs.plugins.graalvm.native)
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set(libs.versions.ktlintTool)
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.logback.classic)
    implementation(libs.sqlite.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlinx.coroutines.test)
    // The conformance runner, so ConformanceSelfTest can run every check against an in-process
    // server. Test-only and pointing this way: :conformance still never depends on :daemon, which
    // is what keeps it a test of the wire contract rather than of this server. The two costs are
    // that any edit under conformance/src/main re-runs this suite, and that the runner's own
    // logback.xml lands on the test classpath — src/test/resources/logback-test.xml outranks it.
    testImplementation(project(":conformance"))
}

// Ktor's server core declares kotlin-reflect; nothing here calls it, and with it on the classpath
// every `typeOf<T>()` (Ktor's own AttributeKeys included) goes through kotlin-reflect, which probes
// the classpath by class name and expects ClassNotFoundException back. A native image built with
// strict registration answers with an error instead, and the first native boot died in Ktor's
// WebSockets class initializer (ADR 2026-11.bxce). Without it `typeOf` is a stdlib intrinsic and
// the image is 9 MB smaller. On `implementation`, which every compile and test classpath extends,
// so `check` runs without it too and a Ktor path that came to need it fails a test here before it
// fails a boot anywhere. Not on every configuration: the Kotlin compiler's own classpath needs it.
configurations.implementation {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

// ---- build stamp ----
// What the daemon reports about itself at runtime: the startup log line, GET /source (the AGPL §13
// offer), and knit_spool_build_info on /metrics.
//
// Both values arrive on the command line and neither is stored in the tree, the rule the version
// already follows. Absent either one the stamp is honestly "unknown" rather than a guess.
//
// Deliberately not shelled out from `git rev-parse` here. processResources feeds the *test* runtime
// classpath, so a value that changed every commit would re-run the whole daemon suite to restamp
// something no local build has a use for — and it is unavailable exactly where it would matter,
// since .dockerignore excludes .git and `docker build .` has no repository to ask.
//
// A generated resource, not a generated .kt: a source file would put that same churn in front of
// compileKotlin. It is also the only mechanism that works in all three run modes — `:daemon:run`
// runs from exploded class and resource directories with no jar anywhere, where a manifest
// attribute reads null.
val spoolCommit = (findProperty("spoolCommit") as String?)?.takeIf { it.isNotBlank() } ?: "unknown"

// Read at configuration time so the task action holds no Project reference.
val spoolVersion = version.toString()

// WriteProperties rather than a doLast { Properties().store() }: store() writes a `#<date>` comment,
// which would make the output differ on every build and defeat both up-to-date checks and the build
// cache. This omits it and sorts keys, so the same inputs always write the same bytes — and its
// `properties` map is @Input, so those two values are the task's declared inputs.
//
// `encoding` stays at its ISO-8859-1 default on purpose: that default keeps unicode escaping on,
// which is what Properties.load(InputStream) expects on the other side.
val buildInfoProperties by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/build-info/build-info.properties")
    property("version", spoolVersion)
    property("commit", spoolCommit)
}

// Under the package directory, not the classpath root: installDist builds a flat 29-jar classpath,
// where a root-level build-info.properties resolves to whichever jar wins the scan. Beside its own
// class, BuildInfo can load it by relative name and the two stay in sync by construction.
tasks.processResources {
    from(buildInfoProperties) {
        into("app/getknit/spool")
    }
}

application {
    applicationName = "knit-spool"
    mainClass.set("app.getknit.spool.MainKt")
    // Sizing target (README): idles in ~128–256 MB on the cheapest VPS tier.
    applicationDefaultJvmArgs = listOf("-Xmx256m")
}

// ---- native image ----
// The aarch64 musl binary for boxes with no JVM, the OpenWrt One first (ADR 2026-11.bxce). Built only
// inside the Liberica NIK musl container on an arm64 host (scripts/native/build.sh): native-image
// cannot cross-compile, and no other native-image distribution runs on musl aarch64. The JVM dist
// above stays the reference artifact. `./gradlew check` never touches any of this; the plugin
// resolves `native-image` only when `nativeCompile` runs.
val nativeOut = layout.buildDirectory.dir("native/nativeCompile")
val hostArch: String = System.getProperty("os.arch")

// libgcc's outline-atomics helpers (the JDK's static libraries call them) read AT_HWCAP through
// `__getauxval`, a glibc-internal name that the NIK container's Alpaquita musl exports and stock musl
// (Alpine, OpenWrt) does not: the image links in the container and fails to load on the router. A
// one-line C shim over the public `getauxval`, compiled with the container's gcc and linked into the
// aarch64 image; src/main/c/getauxval_shim.c says more. x86_64 has no such reference.
val getauxvalShim = layout.buildDirectory.file("native/shim/getauxval_shim.o")
val compileGetauxvalShim =
    tasks.register<Exec>("compileGetauxvalShim") {
        onlyIf { hostArch == "aarch64" }
        val source = layout.projectDirectory.file("src/main/c/getauxval_shim.c")
        inputs.file(source)
        outputs.file(getauxvalShim)
        val output = getauxvalShim.get().asFile
        doFirst { output.parentFile.mkdirs() }
        commandLine("gcc", "-c", "-O2", "-fPIC", "-o", output.absolutePath, source.asFile.absolutePath)
    }

graalvmNative {
    // GRAALVM_HOME, else JAVA_HOME: the container's JDK. Toolchain detection would otherwise go
    // looking for a GraalVM installation on a developer box that has none.
    toolchainDetection.set(false)
    // Off, so the test runtime classpath is exactly what `check` has always run with.
    testSupport.set(false)
    // Reflection, resource and JNI metadata for logback, Ktor, kotlinx and sqlite-jdbc, matched to
    // the versions on the classpath. On by default; named here so the dependency on it is visible,
    // since a Ktor or sqlite-jdbc bump past what it has tested is the likeliest native break.
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            imageName.set("knit-spool")
            buildArgs.addAll(
                // A build that could not analyse everything must fail, not fall back to a bundled JVM.
                "--no-fallback",
                // SIGTERM and SIGINT run the shutdown hook: the 1001 closes and the store close
                // (Main.kt's `serve`). A native image installs no such handlers on its own.
                "--install-exit-handlers",
                // The counterpart of applicationDefaultJvmArgs above: without it Serial GC takes 80%
                // of the box, which on a 1 GB router is everything. `-Xmx` at run time still wins.
                "-R:MaxHeapSize=128m",
                "-H:+ReportExceptionStackTraces",
                // sqlite-jdbc's build-time Feature copies the musl aarch64 libsqlitejdbc.so here
                // instead of embedding it, so the archive ships it beside the binary and no boot
                // extracts a library to a temp dir. The loader finds it next to the executable or
                // under `org.sqlite.lib.path`.
                "-Dorg.sqlite.lib.exportPath=${nativeOut.get().asFile.absolutePath}",
                // A reflection, resource or JNI access nothing registered is a crash naming the
                // missing entry, not a silent null: the acceptance run catches it, production never
                // sees it. The GraalVM-for-JDK-21 spelling; from 24.1 it is --exact-reachability-metadata.
                "-H:+UnlockExperimentalVMOptions",
                "-H:ThrowMissingRegistrationErrors=",
                "-H:MissingRegistrationReportingMode=Exit",
                "-H:-UnlockExperimentalVMOptions",
                // GraalVM 23.1 reads every class's annotations while registering it, and parsing
                // Kotlin's `@Deprecated(level = DeprecationLevel.WARNING)` default initializes this
                // enum in the builder, which the run-time-initialization check then reports as an
                // error. Three constants and no state: build-time initialization changes nothing.
                "--initialize-at-build-time=kotlin.DeprecationLevel",
            )
            // The OpenWrt One's Cortex-A53 has no LSE atomics, and native-image's AArch64 default
            // (armv8.1-a) assumes them: a default build refuses to start there. Host-gated so an x86
            // box can still run `nativeCompile` to see what breaks. The `__getauxval` shim rides
            // along on aarch64 (see compileGetauxvalShim above).
            if (hostArch == "aarch64") {
                buildArgs.addAll(
                    "-march=armv8-a+aes",
                    "-H:+UnlockExperimentalVMOptions",
                    "-H:NativeLinkerOption=${getauxvalShim.get().asFile.absolutePath}",
                    "-H:-UnlockExperimentalVMOptions",
                )
            } else {
                buildArgs.add("-march=compatibility")
            }
            // Extra native-image options for one diagnostic build, never for a shipped one:
            // -PspoolNativeArgs="--trace-class-initialization=a.b.C -H:+PrintClassInitialization".
            providers
                .gradleProperty("spoolNativeArgs")
                .orNull
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                ?.let { buildArgs.addAll(it) }
        }
    }
}

tasks.named("nativeCompile") {
    dependsOn(compileGetauxvalShim)
    // The Feature writes into the export directory; it does not create it.
    doFirst { nativeOut.get().asFile.mkdirs() }
}

// The archive a release ships, and scripts/native-acceptance.sh proves: the binary, its sqlite
// library, and the two files every distribution of this program carries.
val nativeDistTar by tasks.registering(Tar::class) {
    dependsOn(tasks.named("nativeCompile"))
    doFirst {
        check(hostArch == "aarch64") { "the native archive is named aarch64, and this host is $hostArch" }
    }
    archiveFileName.set("knit-spool-${project.version}-linux-aarch64-musl.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP
    into("knit-spool-${project.version}") {
        from(nativeOut) {
            include("knit-spool", "libsqlitejdbc.so")
            filePermissions { unix("0755") }
        }
        from(rootProject.layout.projectDirectory) {
            include("LICENSE", "THIRD-PARTY-NOTICES.md")
        }
    }
}

tasks.test {
    useJUnitPlatform()

    // DeployConfigTest reads deploy/ to pin the shipped compose files against KNOWN_VARS. Gradle
    // cannot infer that from the classpath, so without this the task stays UP-TO-DATE when only
    // those files change -- which is exactly the edit the test exists to catch, and it would pass
    // by never running. Declared as an input so editing a compose file re-runs the check.
    inputs
        .files(rootProject.layout.projectDirectory.dir("deploy"))
        .withPropertyName("deployConfigs")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // BuildInfoTest reads CHANGELOG.md to pin the snapshot version against the newest released
    // section, and for the same reason: cutting a release edits only that file, which is exactly
    // when this task must not be held up to date.
    inputs
        .files(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
        .withPropertyName("changelog")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
