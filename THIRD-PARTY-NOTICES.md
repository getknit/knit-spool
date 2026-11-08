# Third-Party Notices

Knit Spool is licensed under the **GNU Affero General Public License v3.0 or later** (see
[`LICENSE`](LICENSE)). It depends on, and redistributes in its distribution archives, its container
image and its native binary, the third-party open-source components listed below. Every component is under an
**AGPL-compatible** license.

This file is provided for attribution and to satisfy the notice-retention terms of the Apache
License 2.0 (§4) and the MIT License. Where a component ships its own `NOTICE` file, that notice is
incorporated here by reference. The full text of the Apache License 2.0 is available at
<https://www.apache.org/licenses/LICENSE-2.0>.

The dependency graph is deliberately small. It is resolved from `gradle/libs.versions.toml`; the
table below is the runtime closure of `:protocol`, `:daemon`, and `:conformance`, grouped by
project rather than by artifact. Regenerate it with
`./gradlew :daemon:dependencies --configuration runtimeClasspath` (and the same for the other two
modules) whenever a dependency changes.

## Runtime components (shipped in the dist archives and the container image, compiled into the native binary)

| Component | Project | License |
|---|---|---|
| Kotlin standard library, `kotlin-reflect`, `org.jetbrains:annotations` | [JetBrains — Kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 |
| kotlinx.coroutines (`-core`, `-jdk8`, `-slf4j`) | [JetBrains — kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 |
| kotlinx.serialization (`-core`, `-cbor`) — the CBOR wire format | [JetBrains — kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 |
| kotlinx-io (`-core`, `-bytestring`) | [JetBrains — kotlinx-io](https://github.com/Kotlin/kotlinx-io) | Apache-2.0 |
| Ktor — server (CIO engine, WebSockets, forwarded-header) and client (Java engine, WebSockets), with `ktor-http`, `ktor-io`, `ktor-network`, `ktor-serialization`, `ktor-sse`, `ktor-utils`, `ktor-events` | [JetBrains — Ktor](https://github.com/ktorio/ktor) | Apache-2.0 |
| Typesafe Config (`com.typesafe:config`) — pulled in by `ktor-server-core` | [Lightbend — config](https://github.com/lightbend/config) | Apache-2.0 |
| SLF4J API | [QOS.ch — SLF4J](https://github.com/qos-ch/slf4j) | MIT |
| Logback (`logback-classic`, `logback-core`) — the logging backend | [QOS.ch — Logback](https://github.com/qos-ch/logback) | EPL-1.0 **or** LGPL-2.1 (dual) — see the note below |
| sqlite-jdbc (`org.xerial:sqlite-jdbc`) — the persistent store | [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | Apache-2.0 |
| &nbsp;&nbsp;↳ SQLite itself (compiled into sqlite-jdbc's bundled native libraries) | [SQLite](https://www.sqlite.org/) | Public domain |

### Note on Logback's dual license

Logback is offered under **either** the Eclipse Public License v1.0 **or** the GNU Lesser General
Public License v2.1. This project takes the **LGPL-2.1** option, which is compatible with the AGPL:
LGPL-2.1 §3 permits treating a copy as licensed under the GNU GPL version 2 or any later version,
and GPLv3 combines with AGPLv3 under AGPLv3 §13. Logback is used unmodified. The dist archives and
the container image ship it as its own jar files; the native binary (below) compiles it, with the
rest of the runtime closure, into one executable, which is the case the §3 route covers, and the
complete corresponding source of that combined work is this repository plus the versions
`gradle/libs.versions.toml` names. The EPL-1.0 option is *not* GPL-compatible and is deliberately
not the one relied on here.

## Container base image

The [`Dockerfile`](Dockerfile) builds on **[Eclipse Temurin](https://adoptium.net/)** — `21-jdk` for
the throwaway build stage, `21-jre` for the shipped runtime stage. Temurin's OpenJDK binaries are
distributed under the **GPL-2.0 with the Classpath Exception**, which is what makes it correct to
run Knit Spool's own AGPL bytecode on them; the surrounding Ubuntu userland carries its own
per-package licenses. Neither is redistributed by this repository — the image is assembled at build
time from upstream layers.

## Native binary (the `linux-aarch64-musl` archive and the OpenWrt package)

`knit-spool-<version>-linux-aarch64-musl.tar.gz` is a GraalVM native image of `:daemon`: the runtime
components above, compiled ahead of time with the parts of the Java class library they reach and
GraalVM's Substrate VM runtime into one executable (ADR 2026-11.bxce). It is built with
**[BellSoft Liberica Native Image Kit](https://bell-sw.com/liberica-native-image-kit/)**, a
distribution of GraalVM community components on Liberica JDK. The compiled-in class library and VM
runtime are under the **GPL-2.0 with the Classpath Exception**; GraalVM's own components are under
the Universal Permissive License 1.0 and the GPL-2.0 with the Classpath Exception. The binary links
dynamically against the host's **[musl](https://musl.libc.org/)** (MIT) and
**[zlib](https://zlib.net/)** (zlib licence); neither is redistributed. The `libsqlitejdbc.so`
beside it is sqlite-jdbc's own build of SQLite for musl aarch64, under the licences in the table.

## Build-time only (not shipped)

- **Gradle**, including the `gradle/wrapper/gradle-wrapper.jar` checked into this repository —
  [Gradle](https://github.com/gradle/gradle), Apache-2.0.
- **ktlint** and the `org.jlleitschuh.gradle.ktlint` plugin — [pinterest/ktlint](https://github.com/pinterest/ktlint)
  (MIT) and [JLLeitschuh/ktlint-gradle](https://github.com/JLLeitschuh/ktlint-gradle) (MIT).
- **detekt** and its `dev.detekt` Gradle plugin — [detekt/detekt](https://github.com/detekt/detekt),
  Apache-2.0.
- **Kover** (`org.jetbrains.kotlinx.kover`), the coverage plugin —
  [Kotlin/kotlinx-kover](https://github.com/Kotlin/kotlinx-kover), Apache-2.0.
- **kotlin-test** / JUnit Platform, used by the test suites only.
- **GraalVM Native Build Tools** (`org.graalvm.buildtools.native`) and the **GraalVM reachability
  metadata repository** it downloads — [graalvm/native-build-tools](https://github.com/graalvm/native-build-tools)
  and [oracle/graalvm-reachability-metadata](https://github.com/oracle/graalvm-reachability-metadata),
  both UPL-1.0.
- Container images the native build and its packaging run in, each assembled from upstream layers
  and not redistributed: `bellsoft/liberica-native-image-kit-container` (the toolchain,
  [`scripts/native/Dockerfile`](scripts/native/Dockerfile)), `alpine` (the acceptance run), and
  `openwrt/sdk` (the `.apk` and `.ipk`).
- CI-only images referenced from [`.gitlab-ci.yml`](.gitlab-ci.yml), the maintainer's internal
  pipeline: kaniko (Apache-2.0), Trivy (Apache-2.0), `mdl` (MIT), and GitLab's release-cli (MIT).
- CI-only GitHub Actions referenced from [`.github/workflows/`](.github/workflows/):
  `actions/checkout`, `actions/setup-java`, `actions/cache`, `actions/upload-artifact`, `actions/download-artifact`,
  `actions/github-script`, `actions/stale`, `actions/attest-build-provenance`, and `gradle/actions`
  (all MIT); `docker/setup-qemu-action`, `docker/setup-buildx-action`, `docker/login-action`, and
  `docker/build-push-action` (all Apache-2.0); plus
  [`dessant/label-actions`](https://github.com/dessant/label-actions) (MIT) and
  [`anthropics/claude-code-action`](https://github.com/anthropics/claude-code-action) (MIT).

## Referenced, not redistributed

The reverse-proxy configurations under [`deploy/`](deploy/) are written by this project and covered
by its AGPL license. The servers they configure are not part of this repository and are obtained
from their own upstreams: **[Caddy](https://github.com/caddyserver/caddy)** (Apache-2.0) and
**[nginx](https://nginx.org/)** (BSD-2-Clause).

The Spool wire protocol is specified in
[`docs/SPOOL_PROTOCOL.md`](https://github.com/getknit/knit/blob/main/docs/SPOOL_PROTOCOL.md) in the
[Knit](https://github.com/getknit/knit) repository, a separate GPL-3.0-or-later codebase. The two
share a protocol spec and no code.
