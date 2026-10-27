status: review
issues:

- **Ratings-hub family no longer falls to UNKNOWN (2026-07-30 corpus pass).** Three recognition
  gaps in DoorDash 0.230.0's redesigned Ratings area were closed against the 07-29 pull. All three
  are **desk-verifiable from the next pull alone** — no dev-eyes needed on the road. Open the
  in-app Ratings area during a dash (or any time the app is running with capture on), tap into a
  couple of the rating factors, and then check the pull:
  1. **The hub itself** (`Overall rating <N>` + the Silver/Gold/Platinum gem ladder + "Your rating
     factors") must land in `captures/doordash/accessibility.window/ratings/` **with the side-nav
     drawer CLOSED**. Before this change it only classified when the drawer happened to be open —
     the rule was matching the drawer's "Ratings" menu row, not the screen.
  2. **The "Last 30-day orders" drill-down** (the count factor, the one with no "Last 100 …" list)
     must land in `performance_rate_detail/`, not `UNKNOWN/`.
  3. **The Customer-rating drill-down with the band table EXPANDED** (tap the "Points toward your
     overall rating" row to expand) must stay in `customer_rating_detail/` — the hero-score
     container leaves the tree in that render and used to drop the frame to UNKNOWN.
  Anything still landing in `UNKNOWN/` from that area is the item failing — grab the capture id.
  Known, deliberate residual (NOT a bug to report): the hub's parsed rate fields all read `null` on
  the redesign, because the parse is still anchored on the old `textView_title` layout. That was
  already true before this change; re-anchoring the parse is separate, data-enrichment work.
  - Confirmed: 0/2.
  desk: desk-verifiable: inspect closed-drawer ratings, Last 30-day orders and expanded band-table captures for the named classes; keep the documented parse residual separate.
  Triage source: first-line blame 2026-07-30 (`b18fb8c69`, original README L3109).
