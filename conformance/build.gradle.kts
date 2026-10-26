// The conformance runner: a CLI that validates ANY spool implementation over a live WebSocket.
// Depends only on :protocol — never on :daemon — so third-party spools are tested against the
// spec's wire contract, not against this repo's server internals.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
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
    // Not CIO: its TLS client corrupts the process-wide segment pool on close (KTOR-9974, ADR 2026-10.3kgr).
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.websockets)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    // Mints the self-signed certificate behind the wss:// tests. Test-only: nothing shipped needs it.
    testImplementation(libs.ktor.network.tls.certificates)
}

application {
    applicationName = "knit-spool-conformance"
    mainClass.set("app.getknit.spool.conformance.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
