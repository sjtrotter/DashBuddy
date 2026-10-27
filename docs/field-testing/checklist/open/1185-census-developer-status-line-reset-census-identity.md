status: active
issues: #1185

- **Census developer status line + Reset census identity (#1185).** In Developer settings the census
  block shows `Last run <ago> · <outcome token> · Queued: <n>`; after a dash with UNKNOWN screens it should
  read `uploaded <n>` within ~5 min with Queued back to 0. **Copy install id** puts the full UUID on the
  clipboard (paste it somewhere to confirm). Only if you want to exercise the reset: tap Reset census
  identity → Reset, confirm the prefix line changes to a NEW 8-char prefix within a minute (status
  `reset` → `enrolled`/`spool_empty`), then trust the new install server-side (the old one is orphaned).
  - Confirmed: 1/2 (2026-10-05: dev eyes — status line renders correctly; desk — one enrolment, server shows one trusted unrevoked install last seen 10-05)
  Triage source: first-line blame 2026-10-03 (`a670651fa`, original README L166).
