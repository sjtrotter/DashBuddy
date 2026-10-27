status: review
issues: #551

- **🆕 NEW — PII-safe bug-report log export (#551 P2): Data & Privacy → Export Data → Export log.**
  After a real dash (with at least one recognized offer/delivery so INFO milestones exist), go to
  Settings → Data & Privacy → Export Data, scroll to "Export a bug report", pick a folder, export.
  How to tell it's working: (1) open `dashbuddy-log.txt` and **grep your shop's merchant name — ZERO
  hits** (it's INFO+ milestones only, scrubbed at the sink); any `[scrubbed:<marker>]` lines are the
  gate catching a leak. (2) The success line's auto-scrub count is sane (usually 0, non-zero means an
  upstream site leaked and the sink caught it — worth reporting). (3) The firehose (`app.log` in the
  app's files dir) is still **full fidelity** — raw store names present there, as expected (on-device
  only, never exported). (PR #551-P2)
  - Confirmed: 0/2
  desk: desk-verifiable: export a bug-report log from a representative pull and compare its scrubbed INFO stream with the private DEBUG log and scrub count.
  Triage source: first-line blame 2026-07-05 (`ad091273c`, original README L1814).
