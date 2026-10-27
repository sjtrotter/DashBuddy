status: active
issues: #315, #689

- **🆕 NEW — Analytics Time tab: time split, deadhead, on-time gauge, mileage & tax (#315 H4).**
  In the Analytics hub tap the **Time** tab and pick a period (Today / Week / Month / Lifetime). Four
  cards should appear: a **time split** (hero online duration + "online across N dashes", an
  On-delivery / Unattributed stack bar with durations, and Dashes / Avg-dash tiles), a **deadhead**
  card (a % headline + an On-delivery / Deadhead miles bar — deadhead = miles not attached to any
  delivery), an **on-time** gauge ("NN% on time" over the deliveries that carried a deadline, "N of M
  … with a deadline", plus a "typically Xm early/late" margin line), and a **mileage & tax** card
  (period miles + the est. IRS standard-mileage deduction at the current year's rate via the per-year
  lookup — 2026 = $0.725/mi; Lifetime adds a "may span tax years — see the CSV export" note, and a
  Monday-anchored week straddling Jan 1 gets a "spans tax years" note). How to tell it's working:
  the online split ≈ the time you spent online vs on drops for the dash, deadhead miles look sane
  (small on a busy dash, larger if you drove a lot between/after drops), the on-time % and margin match
  how you did against the app's deadlines, and the mileage matches the odometer miles for the period.
  How to tell it's broken: the "coming soon" card still shows, figures don't re-anchor on a period
  switch, a negative deadhead/unattributed value, an on-time gauge counting deliveries that had no
  deadline, or a crash opening the tab with no dashes yet.
  - Confirmed: 1/2 — 2026-07-05: viewed post-dash; no anomalies reported. (Mileage & tax card carries the #689 stale-rate issue.)
  Triage source: first-line blame 2026-07-05 (`9fc69bcb4`, original README L1931). Recorded first confirmation: 2026-07-05 (existing evidence only).
