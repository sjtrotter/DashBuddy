status: active
issues: #551, #692

- **🆕 NEW — Principle-7 logging phase 1: INFO+ log is PII-safe, `SCREEN:` spam gone, real WARNs
  visible (#551 PR #551-P1).** After a dash (ideally one with a **grocery shop**, so the TTS/ShopRate
  paths fire), export/inspect the log (Settings → the log/bug-report export, or pull the on-device
  file). How to tell it's working: **(a)** grep the log for your shop's merchant name (e.g. `H-E-B`,
  `Target`) — it must appear ONLY on `DEBUG` lines, NEVER on `INFO`/`WARN`/`ERROR` (the shareable
  stream). The INFO milestones should read counts-only, e.g. `INFO/Tts: speaking (NN chars)`,
  `INFO/ShopRate: recorded NN items / M.M min = R.RR/min`, `INFO/Chat: offer posted [Persona] (NN
  chars)`. **(b)** The per-frame `SCREEN: <intent>` lines are gone from INFO (now `VERBOSE/Classifier`)
  and the `👻 NULL CHILDREN` noise is gone from WARN (now `VERBOSE/Mapper`). **(c)** The real WARNs —
  `GRACE_COMMIT`/grace-timer wakes, fail-closed gate denials — are now legible in the INFO+ slice
  instead of drowned. How to tell it's broken: any raw store/customer/address text on an INFO+ line,
  `SCREEN:` still at INFO, or WARN still buried under ghost-child noise. (PR #551-P1)
  - Confirmed: 1/2 — 2026-07-05/06 log pull: post-install log has merchant names ONLY at DEBUG, `SCREEN:` gone from INFO (INFO fell to ~1% of lines), real WARNs legible. Residual untagged sites → #692.
  Triage source: first-line blame 2026-07-05 (`77ef51f20`, original README L1870). Recorded first confirmation: 2026-07-05/06 pull (existing evidence only).
