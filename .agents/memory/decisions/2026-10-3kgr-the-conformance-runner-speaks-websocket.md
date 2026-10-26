---
id: "2026-10.3kgr"
slug: the-conformance-runner-speaks-websocket
title: "the conformance runner speaks WebSocket through the JDK"
date: 2026-10-25
topics: [conformance, tls, dependencies]
---

# ADR 2026-10.3kgr — the conformance runner speaks WebSocket through the JDK

Status: Accepted (2026-10-25)

**Context:** against the lab spool over `wss://`, the runner lost checks to exceptions thrown inside
Ktor CIO's TLS client while a new connection opened: `Invalid TLS record type code: 0`,
`IllegalArgumentException: Failed requirement.` from kotlinx-io's `SegmentPool.recycle`, NPEs in
kotlinx-io's `Buffer`, a negative `arraycopy` length. On 2026-10-25, 8 of 19 runs had at least one,
spread over 12 different checks. The run then exits 3 (inconclusive) when every MUST check that
reached a verdict passed (#6). It looked like a flaky check, or like a newer build making it worse.
Neither was true. CI never saw it because every CI path was plain `ws://`.

The cause is upstream, KTOR-9974. CIO's TLS encoder is an actor, and closing its channel launches
a second coroutine that writes `close_notify` to the same socket channel while the encoder may still
be writing the last record. The last record is the WebSocket close frame, so the race comes with
every TLS close, and nothing the runner does around `close` or `cancel` avoids it. Two writers on
one channel corrupt kotlinx-io's process-wide segment pool, and the next connection to open trips
over it. The fix, ktorio/ktor#5943, was still open on this date, and no Ktor release carried it.

On loopback, many `wss://` connections opened and closed back to back reproduce it. Measured on
2026-10-25: CIO faulted 8 of `TlsCycleTest`'s 800 connections, and 24 of 2,200 in the experiments
before it, with the lab's exceptions plus connections that died mid-exchange (close 1006). Another
800 that ended on a large write did not fault. The Java engine faulted none of 4,800.

**Decision:** the runner builds its client in one place, `conformanceHttpClient()`, on
`ktor-client-java`: the JDK's `java.net.http` WebSocket and the JDK's TLS. `ktor-network-tls` and
its pool are off the path. That holds for `ws://` too, so CI's packaged run and `ConformanceSelfTest`
use the engine that ships. The daemon's own tests keep CIO's client: they speak plain `ws://` to an
in-process server, and the daemon serves plain WebSocket behind a TLS proxy.

Alternatives rejected:

- **OkHttp.** It would work, but it adds Square's OkHttp and Okio to the shipped graph, which
  `.agents/rules/coding.md` keeps to Kotlin, Ktor, kotlinx, SLF4J/Logback and sqlite-jdbc. The
  Java engine is Ktor's own.
- **Waiting for KTOR-9974 and bumping Ktor.** That release has no date. Until it ships, a remote
  run cannot be told apart from a flaky one.
- **Retrying `connect` on these exceptions.** It hides a corrupted pool, and the corruption also
  kills connections after the handshake.
- **CIO for `ws://`, Java for `wss://`.** CI would then test an engine that never talks to a remote
  spool.

**Consequences / limits:** the Java engine reports a dead transport in its own ways, and the runner
handles them:

- A socket that dies under a receive arrives as a `WebSocketException` from `incoming`, not a closed
  channel. `nextBinary` makes it a `TransportFailure`.
- Once a send has died, ktor's session can complete `closeReason` with 1000 or 1006 that the spool
  never sent. `expectClose` makes those a `TransportFailure` (`CLOSES_AFTER_A_DEAD_SEND`). A code
  the spool did send still judges it.
- Sends do not back up against the socket, because the engine's outgoing channel is unbounded. A
  blast queues ahead of the wire rather than pacing to it.
- TLS uses the JDK's defaults: its trust store, SNI and hostname verification.

The trap is moving the runner back onto CIO, either to match the daemon's tests or because "Ktor
fixed it". Do that only after a Ktor release that carries ktorio/ktor#5943, with `TlsCycleTest`
passing on CIO and a clean lab comparison. The guards are `TlsCycleTest`, which fails on CIO, the
`wss://` and `expectClose` cases in `SessionTransportTest`, and `conformanceHttpClient` being the
only place `:conformance` builds an `HttpClient`.
