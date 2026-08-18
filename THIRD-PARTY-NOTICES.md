# Third-Party Notices

knit-spool is licensed under the **GNU Affero General Public License v3.0 or later** (see
[`LICENSE`](LICENSE)). It depends on, and redistributes in its distribution archives and container
image, the third-party open-source components listed below. Every component is under an
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

## Runtime components (shipped in the dist archives and the container image)

| Component | Project | License |
|---|---|---|
| Kotlin standard library, `kotlin-reflect`, `org.jetbrains:annotations` | [JetBrains — Kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 |
| kotlinx.coroutines (`-core`, `-slf4j`) | [JetBrains — kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 |
| kotlinx.serialization (`-core`, `-cbor`) — the CBOR wire format | [JetBrains — kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 |
| kotlinx-io (`-core`, `-bytestring`) | [JetBrains — kotlinx-io](https://github.com/Kotlin/kotlinx-io) | Apache-2.0 |
| Ktor — server (CIO engine, WebSockets, forwarded-header) and client (CIO, WebSockets), with `ktor-http`, `ktor-io`, `ktor-network`, `ktor-network-tls`, `ktor-serialization`, `ktor-sse`, `ktor-utils`, `ktor-events` | [JetBrains — Ktor](https://github.com/ktorio/ktor) | Apache-2.0 |
| Typesafe Config (`com.typesafe:config`) — pulled in by `ktor-server-core` | [Lightbend — config](https://github.com/lightbend/config) | Apache-2.0 |
| Jansi (`org.fusesource.jansi:jansi`) — pulled in by `ktor-server-core` | [fusesource/jansi](https://github.com/fusesource/jansi) | Apache-2.0 |
| SLF4J API | [QOS.ch — SLF4J](https://github.com/qos-ch/slf4j) | MIT |
| Logback (`logback-classic`, `logback-core`) — the logging backend | [QOS.ch — Logback](https://github.com/qos-ch/logback) | EPL-1.0 **or** LGPL-2.1 (dual) — see the note below |
| sqlite-jdbc (`org.xerial:sqlite-jdbc`) — the persistent store | [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | Apache-2.0 |
| &nbsp;&nbsp;↳ SQLite itself (compiled into sqlite-jdbc's bundled native libraries) | [SQLite](https://www.sqlite.org/) | Public domain |

### Note on Logback's dual license

Logback is offered under **either** the Eclipse Public License v1.0 **or** the GNU Lesser General
Public License v2.1. This project takes the **LGPL-2.1** option, which is compatible with the AGPL
(LGPL-2.1 §3 permits relicensing a copy under the GNU GPL version 2 or any later version). Logback
is used unmodified and shipped as its own jar files, not linked statically or bundled into any
knit-spool artifact. The EPL-1.0 option is *not* GPL-compatible and is deliberately not the one
relied on here.

## Container base image

The [`Dockerfile`](Dockerfile) builds on **[Eclipse Temurin](https://adoptium.net/)** — `21-jdk` for
the throwaway build stage, `21-jre` for the shipped runtime stage. Temurin's OpenJDK binaries are
distributed under the **GPL-2.0 with the Classpath Exception**, which is what makes it correct to
run knit-spool's own AGPL bytecode on them; the surrounding Ubuntu userland carries its own
per-package licenses. Neither is redistributed by this repository — the image is assembled at build
time from upstream layers.

## Build-time only (not shipped)

- **Gradle**, including the `gradle/wrapper/gradle-wrapper.jar` checked into this repository —
  [Gradle](https://github.com/gradle/gradle), Apache-2.0.
- **ktlint** and the `org.jlleitschuh.gradle.ktlint` plugin — [pinterest/ktlint](https://github.com/pinterest/ktlint)
  (MIT) and [JLLeitschuh/ktlint-gradle](https://github.com/JLLeitschuh/ktlint-gradle) (MIT).
- **kotlin-test** / JUnit Platform, used by the test suites only.
- CI-only images referenced from [`.gitlab-ci.yml`](.gitlab-ci.yml), the maintainer's internal
  pipeline: kaniko (Apache-2.0), Trivy (Apache-2.0), `mdl` (MIT), and GitLab's release-cli (MIT).
- CI-only GitHub Actions referenced from [`.github/workflows/`](.github/workflows/):
  `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`, `actions/download-artifact`,
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

The spool wire protocol is specified in
[`docs/SPOOL_PROTOCOL.md`](https://github.com/getknit/knit/blob/main/docs/SPOOL_PROTOCOL.md) in the
[Knit](https://github.com/getknit/knit) repository, a separate GPL-3.0-or-later codebase. The two
share a protocol spec and no code.
