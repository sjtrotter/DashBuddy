status: review
issues: #975, #976, #1024

- **🆕 NEW — #975 — Analytics → Offers tab (redesign stage 3).** Open Analytics. The second tab is
  now **Offers** (order: Money · Offers · Time · Patterns). On it, check four things.
  (a) **Top pair:** acceptance rate on the left, `~$X` "said no to" on the right, with the
  accept/decline/timeout bar under both. The said-no caption must name its population —
  `est. net across N of M declines with estimates` — and if the verdicts didn't price ANY decline
  it should read as an em-dash plus "none of them priced by a verdict", never `$0.00`.
  (b) **Estimate vs reality:** two bars (Est. / Realized $/hr) + a line reading "Accepted offers
  realized about N% of their decision-time estimate", plus a caption saying how many of the
  window's accepted offers were matched. With <5 matched it should ALSO say "Thin data". A
  realized bar wildly above the estimate one (roughly 2×) on a day you ran **stacked** orders is
  the bug to report — stacked jobs are supposed to be excluded from both sides.
  (c) **The list:** your actual offers, newest first, All/Accepted/Declined/Timed-out chips,
  declined and timed-out rows visibly dimmer, and a `See all N offers` footer that expands in place
  (it should re-collapse to 10 rows when you page the window or change the chip).
  (d) **Cross-check:** the `N of M accepted offers` on the est-vs-reality card and the `Accepted`
  count in the funnel legend must be the SAME M for the same window.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: NOT TESTABLE on this build — the pull's device is
    master @ 3bff50dd (07-30 ~14:22), which predates PR #976 (merged after this build). No redesign
    UI evidence obtainable from this pull.)
  desk: needs field: inspect Offers-tab metrics and paging; #1024 part 1 changed the tab count, not all of these assertions.
  Triage source: first-line blame 2026-07-30 (`4b140112e`, original README L1018).
