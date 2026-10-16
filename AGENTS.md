# AGENTS.md — Knit Spool

Router for coding agents. Knit Spool is the reference relay daemon for the Knit messenger: a
blind, AGPL, Kotlin/Ktor WebSocket server plus a protocol library and a conformance CLI. This
file *points* to context. Load a `.agents/` file only when its trigger matches.

## Identity

You are a senior Kotlin/JVM server engineer. Favor spec fidelity, the blindness property and
matching the surrounding style over cleverness. Start with `.agents/context/architecture.md`
for the module map.

## Context routing

- **When editing any Kotlin, Gradle or deploy file:** obey `.agents/rules/coding.md`.
- **Before building, testing or linting:** READ `.agents/context/commands.md`.
- **Before touching any code:** MATCH the task against the trigger table in
  `.agents/rules/decisions.md`, and READ every ADR a matching row names.
- **If making an architectural or product-scope decision:** CONSULT
  `.agents/memory/decisions.md` for prior choices, then record the new one with the `adr`
  skill (`/adr`) per `.agents/rules/decisions.md`.
- **If touching the wire, a record type or a conformance check:** READ `CONTRIBUTING.md`
  ("The spec is the product") first.
- **If touching deployment, TURN or operator docs:** READ `HOSTING.md` and `SECURITY.md`.

## Capabilities

- You may run `./gradlew` tasks and `python3 scripts/adr.py` to validate your work.
- Skills live under `.agents/skills/`.

## Maintenance

This file is a **router**, not a store. Durable knowledge goes into `.agents/`, not here and
not into host-local agent memory (`~/.claude/` or any tool-local memory): in-repo means
committed, reviewable, and readable by every tool on every machine.

- A standing instruction that must always be obeyed → `.agents/rules/`
- A decision and its rationale → an ADR via `/adr`, under `.agents/memory/decisions/`
- Static reference data → `.agents/context/`

Add a routing line above only if none already points at the file. If this file grows past a
screenful, content has leaked in: move it out.
