# CLAUDE.md

Single source of truth is **`AGENTS.md`**, a dotagents router that points into the `.agents/`
context library (rules, context, memory, skills) on demand. It is imported below so Claude Code
loads it every session. Don't add guidance here; put it in the right `.agents/` file and let the
router point to it.

@AGENTS.md
