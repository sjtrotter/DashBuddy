status: active
issues: #1197, #1198

- **Daily recognition health reaches the server (#1197, PR #1198).** Nothing to do on the dash itself;
  the day's RECOGNIZED/UNKNOWN counts per (platform, app version) are ledgered on-device and posted as
  ONE closed-day report by the first census run after UTC midnight. Next morning: the developer census
  status line should read `health_posted 1` (or `spool_empty` on a later run), and the ops dashboard's
  **Fleet health** table (`https://10.8.0.1:8443/ops/` over the tunnel) shows yesterday's UTC day for
  `doordash` with the phone's observed DoorDash version, Admitted ≈ the dash's recognized frames and
  UNKNOWN > 0, and the per-install disclosure lists the phone's 8-char prefix. A row that never appears
  (status `health_rejected n`) is the thing to report, with the rejection reason from the status detail.
  - Confirmed: 1/2 (desk + server 2026-10-05: fleet row 2026-10-04 · doordash · 8.100.12 admitted 544 / unknown 78; device `healthPosted=1`, no health WARN)
  Triage source: first-line blame 2026-10-03 (`72ff4782a`, original README L156).
