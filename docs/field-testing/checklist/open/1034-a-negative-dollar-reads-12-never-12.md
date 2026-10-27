status: active
issues: #1034, #1044

- **🆕 NEW — #1034 — a negative dollar reads `-$12`, never `$-12`.** `Formats.money`/`money0`/
  `money3` put the sign before the `$` now, so this shows up anywhere a figure can go negative.
  Two places to glance at: (a) **the bubble HUD's `$/hr` hero** on a bad offer, or on an overdue
  task's running-at line — when the verdict is "drop it" and the hero is red, it should read
  `-$12/hr`; (b) **the Money card headline** on a losing window (Analytics → Money):
  `$X came in. -$65.94 went to the car.` A stray `$-` anywhere is the bug back. Also worth a
  glance: a **sub-cent** negative must read a plain `$0.00` with **no** minus in front — but a
  real negative that just rounds small still keeps its sign (`-$0/hr` is correct, not a defect).
  Desk-checkable in part: `grep -c '\$-' shareable.log` over the pull should be 0.
  - Confirmed: 0/2
    - desk 09-05: NOT TESTABLE — device ran the pre-#1044 build.
    - desk 09-09: NOT EXERCISED — `grep -c '\$-' shareable.log` = 0, but no window went negative and the
      worst offer (score 28, `$5.74/hr`) stayed positive.
    - desk 09-20: NOT EXERCISED — `grep -c '\$-' shareable.log` = 0 again; no window went negative.
    - desk 09-21: NOT EXERCISED — `grep -c '\$-' shareable.log` = 0 a third time; no window went
      negative.
  Triage source: first-line blame 2026-08-24 (`9b438b76a`, original README L760).
