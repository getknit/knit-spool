// The protocol library: records, PoW, digest — everything a client, daemon, or conformance
// runner shares. Pure CBOR + JDK; no server, no IO. Third-party JVM spools can depend on this
// alone (AGPL-3.0-or-later).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set(libs.versions.ktlintTool)
}

dependencies {
    api(libs.kotlinx.serialization.cbor)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
