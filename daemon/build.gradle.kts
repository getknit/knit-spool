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

application {
    applicationName = "knit-spool"
    mainClass.set("app.getknit.spool.MainKt")
    // Sizing target (README): idles in ~128–256 MB on the cheapest VPS tier.
    applicationDefaultJvmArgs = listOf("-Xmx256m")
}

tasks.test {
    useJUnitPlatform()
}
