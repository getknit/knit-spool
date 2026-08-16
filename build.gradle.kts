plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
}

group = "app.getknit.spool"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.11.0")
    implementation("io.ktor:ktor-server-cio:3.3.0")
    implementation("io.ktor:ktor-server-websockets:3.3.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("app.getknit.spool.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
