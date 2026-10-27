status: active
section: §4 1/2 items older than 6 weeks / analytics
issues: #315, #659, #694
confirmed: 1/2

- [active] **🆕 NEW — Analytics tile now opens the Money tab (#315 H1 / Money tab v1).**
  From the home screen tap the **Analytics** tile: it should open a real hub (back arrow) with a
  **Money / Patterns / Decisions / Time** tab bar (the last three show a "coming soon" card). On
  **Money**, pick **Today / Week / Month / Lifetime** and confirm the figures re-anchor: the earnings
  hero (gross + True-Net / Net-hr chips), the **gross → −cost step(s) → net** waterfall (3- or 4-step
  per #659 — see the dedicated item below), the
  2×2 tiles ($/hr · $/mi · Miles · Deliveries), top stores, and recent dashes. Cross-check: for the
  **same period** the Money numbers should match the dashboard's tiles (both read the same frozen
  read-model). An **unattributed-pay callout** appears only when that period has bonuses/adjustments.
  How to tell it's broken: the old "Construction Area 🚧" placeholder still shows, figures don't change
  with the period, Money ≠ dashboard for the same window, a crash on an empty period, or a "$0.00"
  unattributed callout on a period with none.
  - Confirmed: 1/2 — 2026-07-05: all tabs viewed post-dash; Money figures re-anchored and matched reality (incl. the unattributed callout). Copy vocabulary note → #694.
  Triage source: first-line blame 2026-07-05 (`7534abd51`, original README L1894). Recorded first confirmation: 2026-07-05 (existing evidence only).
