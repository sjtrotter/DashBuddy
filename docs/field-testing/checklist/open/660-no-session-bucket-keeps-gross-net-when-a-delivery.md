status: review
issues: #660

- **"(No session)" bucket keeps gross ≥ net when a delivery lands without a session (#660 piece
  1).** Only reproducible if a dash produces a `sessionId IS NULL` delivery row (e.g. a straggler
  DELIVERY_COMPLETED after the app/service restarted mid-dash, or any other path that loses
  session context) — may not fire on a normal dash. If it does happen: on the Money tab for the
  period containing that delivery, a new **"(No session): $X across N deliveries not tied to a
  dash"** callout should appear (same style/placement as the existing unattributed/over-attributed
  flags), the hero **Gross Earnings** figure should include that delivery's pay (no longer
  possible for the True-Net chip to show more than Gross), and the per-day chart's bar for that
  delivery's own completion day should include its pay. If you can't force this edge case, this
  item can be validated desk-side by inspecting `delivery_records` for any `sessionId IS NULL` row
  after a dash and confirming the Money tab reflects it as above.
  - **Known caveat (desk-verifiable, not a bug to report):** if the orphan delivery's pay was
    ALSO already inside a surviving session's captured `reportedEarnings` (e.g. the restart
    happened mid-dash and the dash's summary screen still got captured afterward), gross will
    double-count those dollars — expect to see them flagged in BOTH the unattributed callout
    and the "(No session)" callout at once. This is a known, documented overstatement (mirrors
    the pre-existing net-side overlap) that piece 2 (categorizing an orphan into its real
    session) is the actual fix for — no action needed beyond noting it if seen.
  - Confirmed: 0/2.
  desk: desk-verifiable: query sessionId IS NULL deliveries and reconcile orphan gross/net/day totals, retaining the documented overlap caveat until assignment.
  Triage source: first-line blame 2026-07-10 (`cf369fc17`, original README L3090).
