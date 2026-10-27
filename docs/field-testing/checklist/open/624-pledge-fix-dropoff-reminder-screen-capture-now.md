status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #624, #598
confirmed: 0/2

- [review] **🔒 PLEDGE FIX — dropoff-reminder screen capture now masks the address (#624).**
  Hit the "Deliver to door of ‹address›" reminder card on a dropoff and capture it. **Working looks
  like:** the envelope shows `Deliver to door of [redacted:‹4hex›]` — the street/apt gone. (This was
  a live raw-address leak the #598 sha256 gate couldn't catch.) A defense-in-depth backstop also
  scrubs any recognized screen that ships a "Deliver to "/"Order for " customer line a rule forgot
  to redact — if you see a `redactBackstopScrubs=` count climb in the PipelineStats log line, note
  which screen tripped it.
  - Confirmed: 0/2
  desk: desk-verifiable: inspect dropoff-reminder envelopes and recognized-screen backstop output for masked addresses and customer lines.
  Triage source: first-line blame 2026-07-03 (`82cbb53d5`, original README L2027).
