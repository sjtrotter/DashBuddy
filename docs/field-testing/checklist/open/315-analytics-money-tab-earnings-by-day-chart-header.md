status: active
section: §4 1/2 items older than 6 weeks / analytics
issues: #315
confirmed: 1/2

- [active] **🆕 NEW — Analytics Money tab: earnings-by-day chart + header CSV export icon (#315 H6).**
  Open **Analytics → Money** and pick **Week** or **Month**: a new **"EARNINGS BY DAY"** card should
  appear as the **second** card (right under the gross hero), a bar per day of the period — 7 bars for
  Week (labelled M/T/W…), ~28–31 for Month (only the 1/5/10/15/20/25/30 labelled). The **best-earning
  day** should be highlighted in the accent color, gap days you didn't dash show as empty/zero bars,
  and a "gross per day · dashes count on their start day" caption sits below. Cross-check: each bar's
  height should track that day's take, and the sum across the week/month should roughly match the gross
  hero. Then switch to **Today** and **Lifetime** — the chart card should be **absent** on both (one bar
  adds nothing; Lifetime is unbounded). Separately, tap the **download icon in the hub's top bar** — it
  should open the same **Data & Privacy → Export Data (CSV)** screen the Settings row reaches. How to
  tell it's broken: the chart shows on Today/Lifetime, a day's bar doesn't match its earnings, no day
  highlighted on a period where you did earn, missing gap days (a squished <7 bar week), or the header
  icon doesn't open the CSV export screen. (PR #315-H6.)
  - Confirmed: 1/2 — 2026-07-05: header icon used for the CSV export (opens the same Data & Privacy screen); Week chart viewed.
  Triage source: first-line blame 2026-07-05 (`2c32e53ce`, original README L1948). Recorded first confirmation: 2026-07-05 (existing evidence only).
