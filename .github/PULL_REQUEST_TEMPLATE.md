<!--
Thanks for contributing to knit-spool! Keep each pull request focused on a single change.
By submitting, you agree your contribution is licensed under AGPL-3.0-or-later (see CONTRIBUTING.md)
and that every commit is signed off under the DCO (`git commit -s`).
-->

### What and why

<!-- What does this change, and why? Link any related issue (e.g. Closes #NN). -->

### How it was tested

<!-- Check what you ran / did. -->

- [ ] `./gradlew check` (compile, ktlint, spec vectors, both store backends, integration tests)
- [ ] Conformance suite against a locally built daemon (CI's `conformance` job does this too)
- [ ] `--destructive` conformance run, if this touches quotas, rate limits, or shedding
- [ ] Ran the daemon against a real client, if this touches the wire

### Checklist

- [ ] Commits are signed off (DCO): `git commit -s` — see [`DCO`](DCO) and
      [CONTRIBUTING.md](CONTRIBUTING.md)
- [ ] My contribution is licensed under **AGPL-3.0-or-later**
- [ ] No AGPL-incompatible or unverified dependency added
      (updated [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) if a shipped dependency changed)
- [ ] The §13 spec vectors still pass **unmodified** — a failing vector means the change is wrong,
      not the test
- [ ] `:conformance` still depends only on `:protocol`, and any new check is one a third-party spool
      could pass
- [ ] New/changed `SPOOL_*` variables refuse to start on an invalid value and are in the README's
      configuration table
- [ ] Nothing new is logged, persisted, or exported beyond scope ids, blob ids, ciphertext, sizes,
      and timing — and the bearer token stays out of logs
- [ ] `CHANGELOG.md` updated under **Unreleased** if this is operator-visible
- [ ] Code matches the surrounding style

### Compatibility impact

<!--
Wire format, handshake limits, digest, PoW, on-disk schema, or a changed default: say what an
existing client or an existing SQLite store does when it meets this build. "None" is a fine answer.
-->
