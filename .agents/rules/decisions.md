# Decisions

When an ADR must be read, and how a new one is recorded. Routed from
[`AGENTS.md`](../../AGENTS.md): match the trigger table here before touching code,
and read the whole file before recording an architectural or product-scope choice.

## When to read one

Every row names a **trigger** and a **file**. Follow the rows that match the task and
skip the rest. The traps live in the routed files, not here: if a trigger row starts
growing an explanation, that explanation belongs in the ADR.

| If touching | READ |
| --- | --- |
| `ent`, entitlement tickets, `turnreq` gating or `hello.stun` | [ADR 2026-10.bxsg](../memory/decisions/2026-10-bxsg-entitlement-tickets-gate-turn.md) |
| `TURN_*` quotas, `bps-capacity` or the relay port range | [ADR 2026-10.y2ph](../memory/decisions/2026-10-y2ph-the-turn-overlay-s-default.md) |
| `maxScopes`, scope deletion, `reclaimIdle` or `shedOldestScope` | [ADR 2026-10.d2zb](../memory/decisions/2026-10-d2zb-bare-scopes-are-reclaimed.md) |
| `auto_vacuum`, `incremental_vacuum`, `reclaimSpace`, `temp_store_directory` or the store file's size | [ADR 2026-10.us34](../memory/decisions/2026-10-us34-freed-sqlite-pages-go-back.md) |

## Where they live

One file per decision under [`../memory/decisions/`](../memory/decisions/). The index
at [`../memory/decisions.md`](../memory/decisions.md) is a **generated router** over
them.

## Adding one

**Record every decision through the `adr` skill** (`/adr` in Claude Code). It knows
this convention, the lint rules an ADR body must pass, and the supersede procedure.
An agent without that skill runs the same script directly:

```sh
python3 scripts/adr.py new "<title>" --topics a,b   # scaffolds the file, prints the citation
# write the body
python3 scripts/adr.py index                        # regenerates the router
python3 scripts/adr.py lint                         # markdownlint via lintorama
```

**Never hand-edit the router.** `python3 scripts/adr.py index --check` fails instead
of writing, and CI runs it.

`new` deliberately does *not* update the router: a decision appears there once it is
written, not while it is a stub.

Once the body is written, add a row under [When to read one](#when-to-read-one) if the
next task in that area would need the decision. A decision nobody is routed to is a
decision nobody reads.

## Ids are minted, not chosen

A new ADR is `YYYY-MM.suffix` with a random four-character suffix (`ADR 2026-08.k3f9`),
because a sequence number is itself the merge conflict: every branch reads the same
"next number" and takes it. Sequence ids that predate the convention stay valid.

**Cite whichever form an ADR carries, and never renumber one.** The generator refuses a
duplicate id, a missing frontmatter key, a filename that disagrees with its `id` and
`slug`, and a `# ADR …` heading that disagrees with its frontmatter. That last check is
load-bearing: every citation in the codebase resolves by grepping for that heading.

## What belongs in one

An ADR records a decision **and what it cost**. The scaffold's three labels are the
shape:

- **Context**: what was observed, and what it was mistaken for. Numbers where there
  are numbers.
- **Decision**: what changed, and what the alternative was.
- **Consequences / limits**: what this costs, what it does not cover, and the trap
  the next person will reach for. **Name the test or check that keeps it true.**

That last line earns its keep. A decision with no named guard is a decision the next
refactor undoes without noticing.

To reverse a decision, write a new ADR and change the old one's `Status:` line to
`Superseded by ADR <id> (<date>)`. Never delete an ADR: its id is cited in commit
messages that cannot be rewritten.

## Every count in an ADR is point-in-time

An ADR records what was measured **on the day it was written**. A count inside
`memory/decisions/` is evidence for that decision and **never a statement of what is
true now**. Do not quote one as the current figure, and do not carry one into a new
comment, doc or answer. Measure again instead.

## Consult before deciding

Read the router at [`../memory/decisions.md`](../memory/decisions.md) for prior choices
before making an architectural or product-scope call. The ADR is usually faster than
the argument, and a re-litigated decision should end in a new ADR that supersedes the
old one, not in a silent reversal.
