<!--
Bug report for knit-spool. Please search existing issues first.
Note: knit-spool is a best-effort hobby project shipped as-is — issues are welcome but may not be
triaged, answered, or fixed on any timeline (see CONTRIBUTING.md).
Do NOT report security vulnerabilities here — see SECURITY.md for private disclosure.

If the daemon disagrees with docs/SPOOL_PROTOCOL.md, quote the section: the spec wins, and that
makes this a bug here. If the *spec* is what looks wrong, open it in the Knit repo instead.
-->

### Summary

<!-- A concise description of the bug. -->

### Steps to reproduce

1.
2.
3.

### What happened

<!-- Actual behavior, including any error text, `err` code, or daemon log lines. -->

### What you expected

<!-- Expected behavior, and the spec section it follows from if there is one. -->

### Environment

- knit-spool version / commit (or image tag + digest):
- How it runs: `./gradlew :daemon:run` / dist tarball / container / compose (`tls`, `tiny`)
- Store: in-memory or SQLite (`SPOOL_DATA_DIR` set?)
- Reverse proxy in front (Caddy / nginx / other / none), and `SPOOL_TRUST_PROXY`:
- JDK and OS:
- Client: the Knit app, the conformance runner, or your own implementation

### Configuration

<!--
The non-default SPOOL_* variables, with SPOOL_TOKEN redacted. "Defaults" is a fine answer.
-->

### Logs / conformance output

<!--
Daemon log excerpts around the failure, and the relevant `/metrics` counters if they moved. If the
conformance suite catches it, paste the failing TAP lines:

    conformance/build/install/knit-spool-conformance/bin/knit-spool-conformance \
        ws://127.0.0.1:9470/spool/v1
-->
