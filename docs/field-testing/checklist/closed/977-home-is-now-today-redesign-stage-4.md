status: superseded
issues: #977, #978, #1024, #1027

- **🆕 NEW — #977 — Home is now "Today" (redesign stage 4).** Open the app's home screen (not the
  bubble). Top to bottom it should read: **date + a live clock + a status pill** (the pill's word is
  the same status vocabulary the old card showed — Ready / Looking for offers / Heading to Pickup…),
  then **TODAY'S PLAN**, **SO FAR TODAY**, **THIS WEEK**, any review chores, then the four entry
  tiles + Show Bubble. Four things to check.
  (a) **The clock actually ticks** — watch it roll a minute without leaving the screen. A frozen
  clock is the defect.
  (b) **The plan strip:** 24 little cells = *this weekday's* hours across your whole history, green
  where you've earned well. Every hour before the current one must be visibly **dimmed**, and the
  dimming must advance on its own when the hour rolls over (park on the screen across e.g. 5:59 →
  6:00). The headline reads `Best bet tonight: 5–8 PM · your Mondays run $X/hr` with the
  recommended cells **outlined**, and the line under it must ALWAYS read
  `from your own <weekday>s, lifetime — not a guarantee`. On a weekday you've barely worked it must
  say so ("Not enough Mondays on record yet — only N hours…") and show **no rate at all** — a rate
  on a thin weekday is the bug to report. It should never recommend a window that has already
  passed.
  (c) **This week:** kept money for the pay week, a `▲/▼ X% vs last week` line (or "About the same",
  or "Up from nothing" — never a percentage against an empty week), and a 7-point sparkline. Tap it:
  `Recap →` must land in Analytics **already showing this week** — if the hub opens on some older
  window you paged to earlier, that's the bug.
  (d) **Review chores:** if the week has any (unattributed pay, "(No session)" drops, orphan
  offers), they appear as one **NEEDS A LOOK** card, each row ending in `Review →` that also lands
  in Analytics on this week. A clean week must render no card at all.
  Also confirm nothing was lost: the old Today/Week/Month/Lifetime selector is gone on purpose —
  those windows live on the Analytics pager now.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: NOT TESTABLE on this build — the pull's device is
    master @ 3bff50dd (07-30 ~14:22), which predates PR #978 (merged after this build). No redesign
    UI evidence obtainable from this pull.)
  desk: superseded by #1024 part 3 / PR #1027, the later “Home is four blocks” item replaces #977's clock/status/section layout; dev to retain any uncovered plan checks before pruning.
  Triage source: first-line blame 2026-07-30 (`ed1a0eded`, original README L989).
