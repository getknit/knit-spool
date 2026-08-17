<!--
Feature or enhancement proposal for knit-spool. Please search existing issues first.
Note: knit-spool is a best-effort hobby project — proposals are welcome but may not be accepted.

Read this first: the spec is the product. Behavior the spool protocol does not describe is out of
scope for this daemon, and a change to the protocol itself belongs in the Knit repo
(docs/SPOOL_PROTOCOL.md), not here — this repo follows once it lands. See CONTRIBUTING.md.
-->

### Problem / motivation

<!-- What are you trying to do that a spool doesn't support today? -->

### Proposed solution

<!-- What you'd like to see happen. -->

### Alternatives considered

<!-- Other approaches you weighed, and why this one. -->

### Spec impact

<!--
Does this need a change to the wire contract, the handshake's advertised limits, the digest, or the
PoW rules? If so, this is a Knit-repo spec discussion first — link it here. If it needs no spec
change (ops, storage, deployment, performance, config), say so.
-->

### Blindness impact

<!--
A spool sees scope ids, blob ids, ciphertext, sizes, and timing — and must never learn node ids,
plaintext, rosters, or delivery facts. Does this proposal log, persist, export, or derive anything
outside that set? See SECURITY.md.
-->

### Footprint

<!--
The daemon is sized to idle in ~128-256 MB on the cheapest VPS tier. Does this add per-scope or
per-connection state, a background thread, or a dependency?
-->

### Additional context
