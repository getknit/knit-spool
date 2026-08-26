// The reference daemon: WSS server, scope store (in-memory + SQLite), rate limiting, ops.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    application
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

tasks.test {
    useJUnitPlatform()
}
