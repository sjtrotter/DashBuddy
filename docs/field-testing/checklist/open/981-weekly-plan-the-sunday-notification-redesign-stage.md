status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / analytics
issues: #981
confirmed: 0/2

- [review] **🆕 NEW — #981 — Weekly Plan + the Sunday notification (redesign stage 6).** A whole new screen,
  reached two ways: the Sunday-evening notification, and (once you save a plan) a row on Home.
  Six things to check.
  (a) **Getting there:** Home shows no plan row until you save one. Reach the screen the first time
  by waiting for the Sunday ~6 PM notification ("Your weekly plan") and tapping it — it must open
  the Weekly Plan screen, not the home screen. Its notification channel is its own: check
  Settings → Apps → DashBuddy → Notifications lists a **Weekly plan** channel separate from
  **App notices**, and muting one must not mute the other.
  (b) **The numbers are two, always:** the headline `N hours on your best windows ≈ $X kept` must
  ALWAYS be followed by the comparison line `The same N hours placed at random ≈ $Y — the plan is
  worth about $Z`. A headline with no comparison under it is the bug to report. If your record is
  too thin for a baseline it must say so in words instead of showing the headline alone. The line
  `From your own record, lifetime — a projection of what you have earned, not a promise…` must be
  on the card in every state.
  (c) **The windows are evidence-backed:** each row reads `Fridays 5–9 PM`, an evidence line
  `your Fridays: $26.40/hr over 6 Fridays`, and a projected figure. The count is **days, not
  dashes** — that's deliberate. One row carries a `BEST` chip. Below the picked rows, every weekday
  that did NOT make the plan appears dimmed with `NOT PICKED` and a reason
  ("only 2 Sundays on record", "your target filled up with better-paying windows", …). A weekday
  silently missing from both lists is a bug.
  (d) **Editing:** swipe a row left to drop it — the plan must re-fill from *different* hours, not
  put the same window back, and that weekday should now say "you dropped this one". The `‹ ›`
  arrows nudge a window an hour earlier/later, and the evidence line + projected figure must
  **change to the new hours' own numbers** (moving onto hours you've never worked must show
  "no record of these hours — this window projects nothing" and a `—`, never carry the old rate
  along). `Undo` reverses the last change only.
  (e) **Where the plan came from:** the lifetime heatmap (same grid as Analytics → Patterns) with
  the picked cells **outlined** in accent. Cross-check one outlined cell against the window list —
  they must agree.
  (f) **Save + the loop:** tap `Save this plan`. Go Home: a `Your week is planned · Nh across M
  windows · $X projected` row must now appear (and its numbers must match what you saved). The
  following Sunday's notification should lead with the grade —
  `Last week: 7.5 of 12 planned hours worked, $180.00 kept of $280.00 projected` — and the screen
  should show the same four numbers in a card at the top. Skipping the week entirely must read
  "You didn't work any of your N planned hours", not "0 of 12".
  Also check: the two locked rows at the bottom (**PLUS** area demand, **NEEDS A YEAR** year-over-
  year) are dimmed, dashed and carry **no numbers at all** — a fabricated figure there would be the
  worst bug on the screen.
  - Confirmed: 0/2
  desk: needs field: exercise the Sunday notification, plan editor and saved-plan navigation on the device.
  Triage source: first-line blame 2026-07-30 (`ab625bb0b`, original README L927).
