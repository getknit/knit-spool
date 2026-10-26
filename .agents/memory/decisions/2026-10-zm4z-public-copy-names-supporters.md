---
id: "2026-10.zm4z"
slug: public-copy-names-supporters
title: "Public copy names supporters, never payers"
date: 2026-10-25
topics: [docs, tickets, support]
---

# ADR 2026-10.zm4z — Public copy names supporters, never payers

Status: Accepted (2026-10-25)

**Context:** the README, `HOSTING.md`, the deploy files and a few KDoc comments described the
ticket gates in selling words: an operator who "sells TURN", "paid and free callers", "a paying
peer's relay", "one payer", "one account". The support pages called this a "hobby project". Knit
itself is funded by its supporters, and the perks it gives them on its own servers are a thank-you,
not a product. The selling words also misdescribed the daemon: a ticket is a capability an operator
chooses to grant, and nothing in the AGPL daemon is held back from someone running their own Spool.
The TURN section still said Knit's client half had not shipped, which stopped being true with Knit
2.10.0.

**Decision:** public copy in this repo names people by what they hold, not what they pay. A caller
is a **ticket holder** or a caller without a ticket; an operator **offers** TURN or files to ticket
holders; the people who fund Knit are **supporters**, who **receive** perks as a thank-you. The
README's Support section states the funding, the thank-you without figures (it links to the
supporter page instead), the cost reason, and that Knit is its developer's full-time job. The
Deploy section says plainly that a self-hosted Spool sets its own attachment size and TURN, with
no gate unless `SPOOL_TICKET_KEY` is set. "Hobby" becomes "maintained by one person as their
full-time job"; "best-effort" stays. The alternative, leaving the operator docs neutral and putting
the wording only in the app and website, would keep this repo contradicting them.

**Consequences / limits:** wording only. Identifiers keep their names (`payer` in `SpoolServer`,
`--account` in `ticket-mint`, test names such as `theScopeLocksAgainWhenItsPayerLeaves`), because
renaming code to match prose is churn with no reader. Shipped `CHANGELOG.md` sections and earlier
ADRs keep their words, since they are records. Knit Hosted copy (plans, customers, billing) is
unchanged: it does sell instances. Nothing checks this mechanically; before merging docs, grep for
`paying`, `payer`, `sells`, `hobby` and `account` outside code identifiers and shipped sections.
