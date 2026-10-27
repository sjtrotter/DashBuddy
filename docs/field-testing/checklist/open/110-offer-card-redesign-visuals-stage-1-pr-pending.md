status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / HUD
issues: #110
confirmed: 0/2

- [review] **Offer card redesign — visuals (#110 Stage 1, PR pending).** When an offer arrives, glance
  at the bubble offer card and confirm the new layout reads at a glance: a **score ring** (green/
  amber/red by score) beside the **net $/hr** hero; a **verdict banner** (ACCEPT / DECLINE /
  MANUAL REVIEW + one-line reason + quality chip, tinted to match); **badge pills** when present
  (e.g. High pay / Red Card / Alcohol); and a **live expiry countdown** ticking in the header with
  a depleting progress bar. (No Accept/Decline buttons yet — Stage 2.) Watch for: missing/garbled
  values, countdown not ticking or width-jittering, ring color not matching the verdict.
  - Confirmed: 0/2.
  desk: needs field: inspect the offer card's ring, hero, banner, badges and ticking expiry on the device.
  Triage source: first-line blame 2026-06-09 (`797b4c59b`, original README L2854).
