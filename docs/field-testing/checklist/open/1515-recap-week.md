status: active
issues: #1515

- **#1515 (PR #1555) — Recap is the week.** Open Recap mid-week, after a shift has ended and with another day off.
  What to watch: each day's bar matches that day's shift pages (a two-shift day opens the day page, whose Earned is
  the sum of its shifts); a day off reads "No shift" and draws no bar; days still ahead show only last week's dot; the
  comparison under the total names the completed days ("Mon to Tue") and the same days last week, and disappears while
  a shift is running; "Cash included" appears only when the week had cash; Shifts to check lists the shift the shift
  page flags, and a tap lands on the flagged row. How to tell it works: the week's total equals the sum of the day
  bars, and nothing shows $0.00 for a day with no shift.
  - Confirmed: 0/2.
