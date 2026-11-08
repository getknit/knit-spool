---
id: "2026-11.bxce"
slug: a-native-aarch64-musl-binary
title: "a native aarch64 musl binary is built with GraalVM, and the JVM dist stays the reference"
date: 2026-11-07
topics: [build, deploy, native]
---

# ADR 2026-11.bxce — a native aarch64 musl binary is built with GraalVM, and the JVM dist stays the reference

Status: Proposed (2026-11-07)

**Context:** The daemon is pure JVM. HOSTING.md sizes it at `-Xmx256m` and about 450 MB resident on
a 1 GB VPS, and the install paths are the container image and the JVM dist archives. The OpenWrt
One (MediaTek MT7981B, two Cortex-A53 at 1.3 GHz, 1 GB of RAM, 256 MB of SPI NAND, an M.2 slot,
musl 1.2.5) has no JVM in its package feeds and could not spare the JVM's footprint if it had one.
That reads like a porting problem. It is a runtime problem: nothing in the tree is
platform-specific, and the one native dependency, sqlite-jdbc, already ships a `Linux-Musl/aarch64`
library. What was missing was a way to run the same bytecode without a JVM.

Three facts, checked on 2026-11-07, fix the shape of the answer:

- `native-image --static --libc=musl` is Linux x64 only; GraalVM's compatibility page lists musl as
  unsupported on AArch64 (oracle/graal#5872, open since 2023), and native-image cannot
  cross-compile. A musl aarch64 binary therefore has to be built *on* a musl aarch64 host, and is
  dynamically linked against that host's libc.
- The only native-image toolchain that runs on such a host is BellSoft's Liberica NIK 23.1
  (JDK 21), whose `jdk-21-nik-23.1.12.1-musl` container image is published for arm64. BellSoft's
  support matrix marks musl aarch64 "evaluation and testing". GraalVM CE no longer ships JDK 21
  builds, and Oracle GraalVM for JDK 21 moved to the OTN licence at 21.0.13.
- native-image's default `-march` on AArch64 is `armv8.1-a`, which assumes the LSE atomics. The
  MT7981B reports `fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid` and no `atomics`, so a default
  build refuses to start there with a CPU-feature error.

The source itself needs little. The CBOR records were looked up by reflection in `:daemon` and
`:conformance` only because those modules never applied the serialization plugin (33 sites in
`SpoolServer.class`; `:protocol` already intrinsified the same calls). Logback configures itself
from XML by reflection. A native image installs no SIGTERM handler. Two classpath resources,
`build-info.properties` and `logback.xml`, have to be named.

**Decision:** `:daemon` also compiles to a native executable, shipped as
`knit-spool-<version>-linux-aarch64-musl.tar.gz` beside the JVM dist archives on every GitHub
release. It is built by `org.graalvm.buildtools.native` inside the Liberica NIK musl container on an
arm64 host. The JVM dist and the container images remain the reference artifacts; the native binary
is for boxes that cannot run them.

- **Dynamically linked against musl**, never `--static`: static musl is unavailable on aarch64, and
  a static musl executable could not `dlopen` sqlite-jdbc's JNI library anyway. The binary needs
  `libc.musl-aarch64.so.1` and `libz.so.1` from the host and nothing else.
- **`-march=armv8-a+aes`**, host-gated in the Gradle block (`-march=compatibility` elsewhere), so the
  image runs on the Cortex-A53. The AES extension the SoC does have stays in use.
- **One binary is both tested and shipped.** `--no-fallback`; `--install-exit-handlers`, so SIGTERM
  runs the shutdown hook (the 1001 closes and the store close); `-R:MaxHeapSize=128m` as the
  counterpart of the start script's `-Xmx256m` (the executable still takes `-Xmx` at run time); and
  strict missing-registration errors, so an unregistered reflection or resource is a crash in the
  acceptance run rather than a `null` in production.
- **The serialization plugin is applied to every module**, so `RecordCodec.encode<T>()` compiles to
  `T.serializer()` wherever it is inlined. No per-class reflection metadata is needed for records.
- **Logback stays, initialised at run time**, from the same `logback.xml`, through the reachability
  metadata repository. `${SPOOL_LOG_LEVEL:-INFO}` and the `io.ktor` INFO pin that keeps the bearer
  token out of the log work exactly as on the JVM.
- **`libsqlitejdbc.so` is exported beside the binary** at build time (`org.sqlite.lib.exportPath`)
  instead of being extracted to a temp directory at every boot. The loader finds it next to the
  executable, or wherever `org.sqlite.lib.path` points.
- **Built on GitHub's arm64 runners only.** The internal GitLab runners are x86_64 and
  unprivileged, and native-image cannot cross-compile. A `native-aarch64` job runs on every PR and
  on every release.
- The first startup line and `knit-spool check` name the runtime (`BuildInfo.runtime`: `jvm` or
  `native`), because version and commit are identical by construction and a support question
  starts in the log.

The alternatives:

- **A side-loaded JVM.** Temurin publishes an Alpine aarch64 JRE, so the JVM dist does run on the
  router at `-Xmx128m`. It is the fallback if the NIK toolchain proves unusable, at three to four
  times the resident memory and a JIT warm-up on every boot, and OpenWrt has no package shape for
  it.
- **Docker on the router.** The OpenWrt One can run `dockerd` from an NVMe disk, and the arm64
  image exists. It adds the daemon's memory to Docker's own on a 1 GB box, for a service that then
  still runs as a JVM.
- **Static musl via Oracle's toolchain**, or Mandrel: neither exists for aarch64 musl.
- **Waiting for upstream.** #5872 has had no movement since the 2025 comments from both vendors.

**Consequences / limits:** Builds happen only on arm64 hosts; a developer without one builds on an
ephemeral Hetzner CAX box or lets CI do it. BellSoft's "evaluation and testing" status for musl
aarch64 is a real risk and is named in SECURITY.md's known limits; if it bites, the fallback above
is a day's work. The LGPL reasoning in THIRD-PARTY-NOTICES.md changes: logback is compiled into the
binary rather than shipped as its own jar, so the notice rests on LGPL-2.1 §3 and the availability
of the combined work's source, not on separate linking. The reachability metadata repository had
tested Ktor only to 3.5.1 and sqlite-jdbc only to 3.41 on the day this was written; a Ktor or
sqlite-jdbc bump is the likeliest thing to break the native build, which is why the build runs on
every PR rather than on tags alone.

Not covered: an x86_64 native binary (nearly free once this exists, since musl x86_64 is fully
supported), a native conformance CLI, a native container image, and running the JVM test suite
natively (`nativeTest`). The daemon's measured footprint on the OpenWrt One is recorded in
HOSTING.md's appliance section, not here, and this ADR moves to Accepted once that pass is done.

The trap is a `--initialize-at-build-time` entry that reaches logback. It makes the build pass and
freezes `SPOOL_LOG_LEVEL` at whatever the build host had, and the `io.ktor` pin with it. Guards:

- The `native-aarch64` CI job runs `scripts/native-acceptance.sh` against the extracted archive on a
  plain Alpine arm64 container: the `NEEDED` set, an LSE-instruction scan outside GCC's
  outline-atomics helpers, `check` through a symlink and with `-Xmx`, the conformance suite with
  tickets, TURN and a commons room on, `SPOOL_LOG_LEVEL=DEBUG` honoured with `io.ktor` still
  pinned, a SIGHUP reload, the build stamp at `/source`, and a SIGTERM that leaves no
  `spool.db-wal` behind.
- `NoReflectiveSerializerLookupTest` fails if any `:daemon` class again contains a
  `Reflection.typeOf` serializer lookup.
- `BuildInfoTest.theRuntimeIsTheJvmHere` pins the other value of the runtime label.
