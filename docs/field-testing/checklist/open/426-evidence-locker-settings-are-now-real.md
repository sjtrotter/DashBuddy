status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / other
issues: #426
confirmed: 0/2

- [review] **Evidence Locker settings are now real (#426).**
  Screenshots (offer / delivery / dash-summary PNGs in Pictures/DashBuddy) previously fired
  unconditionally; they are now gated on the Evidence settings, whose master toggle defaults
  OFF. On a dash with settings untouched: working = NO new PNGs appear at all. Then flip
  Master Record + a category on mid-dash: working = only that category's screenshots appear.
  Broken = PNGs appear with master off, or an enabled category stops capturing (look for
  "Evidence capture suppressed" in logs with an unexpected category).
  - Confirmed: 0/2
  desk: needs field: toggle Evidence master/categories mid-session and compare the resulting screenshot files with enabled categories.
  Triage source: first-line blame 2026-06-12 (`80058c9f3`, original README L2691).
