---
id: "2026-10.8ymn"
slug: main-builds-carry-a-numbered
title: "main builds carry a numbered dev version that moves with the environment contract"
date: 2026-10-16
topics: [versioning, config, release]
---

# ADR 2026-10.8ymn — main builds carry a numbered dev version that moves with the environment contract

Status: Accepted (2026-10-16)

**Context:** Every `main` build since v0.3.0 reported `0.4.0-SNAPSHOT`. It is the version at
`GET /source` and in `knit_spool_build_info`, and the `org.opencontainers.image.version` label
that `ci.yml` stamps on every `edge` and `sha-` image. The label could not tell one build from
another. `sha-4d0fcf4` (2026-10-10) has no tickets and `sha-1fe816a` (2026-10-16) reads the full
ticket and TURN environment, and both carry the same label. A tool that renders a spool's
environment for an image it did not build has to know which variables the daemon inside will
read. A daemon handed one it does not know logs `unrecognized environment variable … — typo?`
and boots anyway, so a guess that is too new fails silently: `check` passes and the feature is
simply missing. The image label is the one fact such a tool can read off any image, and a
`-SNAPSHOT` label gives it nothing to order.

**Decision:** The development version is `<next minor>-dev.<n>`, starting at `0.4.0-dev.1`. On
`main`, `n` is bumped whenever the **environment contract** moves:

- a `KNOWN_VARS` entry is added or removed;
- a field moves between `bootOnly()` and `reloadable()`, the restart-only and the `SIGHUP` halves;
- a resolved default changes, including those that only appear with tickets or TURN set, such as
  `SPOOL_TICKET_ATTACH_BYTES`.

After a release, the release commit resets the literal to the next minor's `-dev.1`, the way
it bumped the `-SNAPSHOT` before. Under semver precedence a `-dev.<n>` sorts below the release it
leads up to and above the one before it, so a consumer that orders versions needs no special
case when a release lands. `release.yml` refuses a `-dev.` tag: that form names `main`'s builds,
and a release candidate is `-rc.<n>`.

The alternative a reader reaches first is to keep `-SNAPSHOT` and let consumers gate on the
commit, or on a hand-kept list of digests. Commits and digests cannot be ordered, the list rots,
and both put the knowledge of what changed outside the repo that changed it. A number bumped on
every commit is no better than the commit itself: what a consumer needs is how far the contract
has moved, not how many builds there have been.

**Consequences / limits:** `EnvContractTest` pins a fingerprint of the contract (every
`KNOWN_VARS` name, each field's half, and every default the boot log prints, with tickets and TURN
switched on) beside the development version that last moved it. It fails when either moves without
the other. Its failure prints the contract and the new fingerprint, so the fix is to bump `n` and
paste both. `BuildInfoTest` now reads the literal out of `build.gradle.kts` rather than the stamp.
It requires the `-dev.<n>` form and checks that the literal leads the newest `CHANGELOG.md` release,
on release builds too.

Not covered:

- Behaviour that is not in the boot log, such as whether a reloadable field applies immediately
  or only to the next connection. That lives in comments, and a change to it still needs a
  by-hand bump.
- The commons bounds, which the boot log prints on their own line.
- Behaviour behind a variable whose name, half and default all stay the same.

The trap is editing the pinned fingerprint without bumping `n`. That makes the test pass and
publishes a contract change under the old number, which is the failure this exists to prevent.
Review is the only guard against it.
