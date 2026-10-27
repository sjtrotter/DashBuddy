status: active
section: §4 1/2 items older than 6 weeks / analytics
issues: #319, #689
confirmed: 1/2

- [active] **🆕 NEW — CSV data export (#319).** Settings → Data & Privacy → "Export Data (CSV)" → tap
  "Choose folder & export" and pick a folder (e.g. Downloads). Three files should appear:
  `deliveries.csv`, `sessions.csv`, `summary.csv`. Open each in a spreadsheet app: deliveries should
  have one row per completed drop (date/time, platform, store, pay, tip, miles, minutes, net…),
  sessions one row per dash (start/end, duration, odometer start/end, miles, offer counts), and
  summary a totals block with **one `tax_year` group per year present** (miles × the IRS standard
  business rate for that year via the per-year lookup — 2025 = $0.70, 2026 = $0.725; a future year
  with no published rate falls back to the latest rate + a `rate_note` disclaimer). **How to tell it's working:** values are sane (money looks like `8.50` not `0.00`
  everywhere; store names with commas like "Chili's, Cedar Park" stay in one cell, not split), and
  NO customer names/addresses or hashes appear anywhere. All-time export (v1 has no date-range
  picker). Screens with no dashes yet just yield header-only files — that's fine.
  - Confirmed: 1/2 — 2026-07-05: exported via the hub header icon; files sane; found the stale-2025-rate bug → #689 (the export itself worked as built).
  Triage source: first-line blame 2026-07-05 (`b759ac830`, original README L1857). Recorded first confirmation: 2026-07-05 (existing evidence only).
