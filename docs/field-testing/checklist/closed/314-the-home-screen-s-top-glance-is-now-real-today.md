status: superseded
issues: #314, #320, #1024, #1027

- **🆕 NEW — the home screen's top glance is now REAL "Today" totals from the read model (#314 PR3, completes #314).**
  Open the DashBuddy main app (not the bubble). **Working looks like:** the top row of three stat
  tiles — **True Net · Net/hr · Miles**, each sub-labelled **"Today"** — shows your **whole day's**
  frozen net (Σ each completed delivery's frozen net + any unattributed pay), not just the current
  dash, and it **grows within a few seconds of each delivery receipt** (the projector folds the
  completed delivery → Room re-emits the flow → the tile updates, no app restart, no state
  transition). While a dash is running a second **"This dash"** row appears below it (the live
  per-second ticking glance from #320). How to tell it's right: at end of day the Today **True Net**
  ≈ your DoorDash app's earnings for the day minus your operating costs, and **editing the Economy
  settings (gas price etc.) must NOT change a past day's Today number** — historical net is frozen.
  At local **midnight** the Today figures should reset to the new day without reopening the app.
  Broken = a Today that only reflects the current dash, a number that changes when you edit economy,
  a Today that never grows after a delivery completes, or one that doesn't roll over at midnight.
  - Confirmed: 0/2
  desk: superseded by #1024 part 3 / PR #1027, the later “Home is four blocks” item replaces the three Today tiles plus separate This dash row; dev to carry forward uncovered midnight/frozen-history checks before pruning.
  Triage source: first-line blame 2026-07-04 (`07e3092d4`, original README L1987).
