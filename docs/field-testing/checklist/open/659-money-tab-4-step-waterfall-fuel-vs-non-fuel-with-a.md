status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / analytics
issues: #659, #668, #1133, #1219
confirmed: 0/2

- [review] **🆕 NEW — Money tab 4-step waterfall: Fuel vs Non-fuel, with a clean fallback on mixed periods
  (#659).** After the v10 refold, open **Analytics → Money** on a period whose deliveries all carry
  the frozen fuel/non-fuel split (a period entirely dashed after the #668 data-side merge should
  qualify): the waterfall should show **4 rows** — Gross → −Fuel → −Non-fuel → Net — and Fuel +
  Non-fuel should visually sum to the old "Operating cost" gap. Then check a period that **mixes**
  pre-split (fallback) deliveries with frozen ones (e.g. Lifetime, or a week straddling the merge):
  the waterfall should **silently fall back to the 3-step** Gross → −Operating cost → Net shape — no
  broken numbers, no partial-coverage row, no crash. How to tell it's broken: a 4-step render on a
  mixed period (Fuel+Non-fuel not summing to Gross−Net), a 3-step render on an all-frozen period
  (coverage guard too strict), or Fuel/Non-fuel bars rendering negative/nonsensical.
  - Confirmed: 0/2
  desk: needs field: compare all-split and mixed-period cost cards against #1133 / PR #1219's newer coverage guard; check the actual rendered fallback.
  Triage source: first-line blame 2026-07-05 (`005505088`, original README L1907).
