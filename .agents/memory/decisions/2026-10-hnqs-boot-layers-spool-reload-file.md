---
id: "2026-10.hnqs"
slug: boot-layers-spool-reload-file
title: "boot layers SPOOL_RELOAD_FILE over the environment, as SIGHUP does"
date: 2026-10-25
topics: [config, reload]
---

# ADR 2026-10.hnqs — boot layers SPOOL_RELOAD_FILE over the environment, as SIGHUP does

Status: Accepted (2026-10-25)

**Context:** `SPOOL_RELOAD_FILE` was read only by the `SIGHUP` handler. `serve()` and `knit-spool
check` configured from `System.getenv()` alone, so every restart (an upgrade, a reboot, a redeploy)
fell back to the environment for each line the file set. With tokens and quotas that looked like
an inconvenience. Rotating a commons room through the file (ADR 2026-10.hs75) showed it was a
security bug. The next restart brought the old room back with the removed member's invite working
again, and made the new room vanish for everyone who had moved to it. The README's own reload
example, `SPOOL_TOKEN=old` and `SPOOL_TOKEN_NEXT=new` in the file, had the same flaw: a restart
mid-rotation fell back to whatever token the environment still named.

**Decision:** Boot and `check` configure from `bootEnvironment(environment)`: the environment with
the file layered over it, exactly the overlay a `SIGHUP` builds. A restart and a reload therefore
always read the same configuration.

- **A missing file** counts as no overrides. A spool is provisioned with the path before its first
  reload writes anything.
- **Any other read failure** stops the boot (and fails `check`). Booting on the environment alone
  would quietly undo whatever the file holds, which is the failure this exists to remove. That is
  stricter than a reload, which keeps the running configuration on the same failure: a running
  spool has something better than the environment to fall back to, and a booting one does not.
- **The file's path** comes from the environment only. A `SPOOL_RELOAD_FILE` line inside the file
  is dropped with a warning.
- **Boot-only lines** in the file now take effect at boot. A reload already logged them as
  "needs a restart", and now the restart honours them.
- **The boot log** names the variables the file sets over the environment, never their values.

The alternative a reader reaches for first is documentation: tell operators to write each change
to both the file and the environment. That keeps boot simple and leaves a one-step slip that
silently re-admits a removed member, discovered only after the next reboot. A boot-time warning
when the two disagree is half of that fix, and still leaves the old room running.

**Consequences / limits:** Boot behaviour moved for every reloadable setting, tokens included, so
an operator who kept stale lines in the file will see them applied at the next boot. The file must
live where the container keeps it across recreation, as the compose files already say
(`/data/reload.env`). An unreadable file is now a boot failure where it used to be ignored at boot.
Deleting a line still falls back to the environment, so removing a value the environment sets takes
a blank line. That works for tokens, room ids and names, and `SPOOL_COMMONS_RATE_TOTAL`. Integer
settings other than that rate still refuse a blank value.

The trap is a new boot-time read of `System.getenv()` that bypasses `bootEnvironment`. Such a read
sees the environment without the file, and is wrong in exactly the case this fixes. Guards:
`BootEnvironmentTest` covers the layering, the missing and unreadable files, the nested path, and
`check` reporting what the file sets.
