// The conformance runner: a CLI that validates ANY spool implementation over a live WebSocket.
// Depends only on :protocol — never on :daemon — so third-party spools are tested against the
// spec's wire contract, not against this repo's server internals.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
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
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
}

application {
    applicationName = "knit-spool-conformance"
    mainClass.set("app.getknit.spool.conformance.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
