status: active
issues: #315

- **🆕 NEW — Analytics Decisions tab: offer funnel + value-of-saying-no + score-vs-outcome (#315 H3).**
  In the Analytics hub tap the **Decisions** tab and pick a period (Today / Week / Month / Lifetime).
  Three sections should appear: an **offer funnel** (a stacked Accepted / Declined / Timed-out bar +
  legend, with the **acceptance rate** as the big headline and the offer count under it), a **value of
  saying no** card ("~$X est. net skipped across N declined offers"), and a **score vs outcome**
  comparison (avg score + avg **est.** $/hr for Accepted vs Declined). How to tell it's working: the
  Accepted/Declined/Timed-out counts and the acceptance rate should match what you actually did on the
  dash; value-of-saying-no ≈ the sum of the estimated net of the offers you declined; every economic
  figure is labelled **"est."** (these are the offer's frozen decision-time estimates, not realized
  pay). How to tell it's broken: the "coming soon" card still shows, counts don't match the dash,
  figures don't re-anchor on a period switch, a "$0.00"/blank where an em-dash should be on an empty
  period, or a crash opening the tab with no offers yet.
  - Confirmed: 1/2 — 2026-07-05: viewed post-dash; funnel counts matched the dash (5 accepted / 3 declined, no timeouts).
  Triage source: first-line blame 2026-07-05 (`f5cac9467`, original README L1918). Recorded first confirmation: 2026-07-05 (existing evidence only).
