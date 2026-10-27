status: active
issues: #693, #722

- **🆕 NEW — bubble post-dash card shows the TRUE last session + gas/vehicle quick actions (#693 / PR).**
  The idle bubble card now reads the analytics read-model (`recentSessions(1)`) instead of an in-memory
  capture that could miss a dash. **Primary check — the $0 repro:** end a dash that earned nothing (accept
  one order then unassign, or just go online→offline with no completions), collapse the bubble, then reopen
  it. The idle card AND the dimmed top-bar "LAST SESSION" must reflect **that** dash (earnings, miles,
  duration, deliveries, acceptance %), **not** an older moneyed dash. The "ended Xm ago" caption should tick
  up live. A live dash shows full-opacity "THIS SESSION". **Vehicle:**
  tap Vehicle → the main app opens directly on the Personal Economy screen. Anything stale, or a $0 dash
  getting skipped, is a regression — capture it. (The gas quick-edit sub-check is superseded by the
  mode-adaptive #722 item below — the plain stepper-tap-flips-mode UI this item originally described no
  longer exists in AUTO mode.)
  - Confirmed: 1/2 (07-07 on-device screenshot, post-reachability-fix: the idle card showed the TRUE
    last session — the 07-05 **$0** dash, `$0.00 · 6.9 mi · ended 43h ago`, exactly matching
    `session_records` — i.e. the primary $0-repro check passed: a $0 dash was NOT skipped for an
    older moneyed one.)
  - Desk replay fixture: `~/dashbuddy/logs/2026/07/06/` (the 07-05 $0 session `session-doordash-1783294721320-15`).
  Triage source: first-line blame 2026-07-06 (`0329e5d9e`, original README L1687). Recorded first confirmation: 2026-07-07 screenshot (existing evidence only).
