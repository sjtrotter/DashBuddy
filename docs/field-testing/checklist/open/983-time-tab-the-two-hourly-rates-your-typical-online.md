status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / analytics
issues: #983
confirmed: 0/2

- [review] **🆕 NEW — #983 — Time tab: the two hourly rates, your typical online hour, gap stats
  (redesign stage 7/7 — the epic's last build).** Analytics → **Time**, after a dash or two.
  Four things to check.
  (a) **NET PER HOUR** shows two tiles: *While working* and *Whole shift*, each with its own
  duration underneath. **While working must never be lower than whole shift** — same money over a
  smaller denominator. If they are identical, read the line under them: it should say no gap was
  measured in this window (which is itself a finding — see (c)). The sentence between them explains
  the difference; the last line must still say net is frozen at accept-time costs.
  (b) **YOUR TYPICAL ONLINE HOUR** is a 3-segment bar (driving & other / at stops / waiting) whose
  three chunks add up to one hour. Sanity-check it against the dash you just did: if you spent most
  of an hour parked at a Walmart, *at stops* should be the fat segment. Under it, a dollar line —
  `Waiting cost you about $X in this window` — and a coverage line saying how many of your stops
  were timed. **The coverage line is the one to watch**: if it says "no stop recorded an arrival",
  the arrival stamps aren't landing and the segment is meaningless (report it).
  (c) **GAPS BETWEEN JOBS** — typical / 9-in-10-under / longest, plus `N gaps measured across M
  drops`. Compare *typical* against your gut for that dash. Two failure shapes to catch: a gap that
  looks like it spans **two different dashes** (impossible by design — the fold refuses it, so
  seeing one means the session ids are wrong), and `0 gaps measured` on a dash where you clearly
  waited between orders (means accepts or completions aren't being recorded in-session).
  (d) **Page the window** (‹ ›) and confirm every figure on the tab moves together — no tile left
  quoting last week's number under this week's label.
  - Confirmed: 0/2 (desk 08-09, the 08-01→08-08 window: the tab itself is a UI check no pull can
    answer, but the **gap fold underneath (c) was traced against the log** and behaves as designed:
    4 of the 5 dashes measure coherently — 7 gaps spanning 0.6–2.7 min, 5 dash-final drops
    correctly excluded as tails, zero cross-dash and zero cross-day pairings. The fifth (08-01)
    measures **0 gaps despite a real ~8-minute wait**, and the mechanism is now understood: that
    dash's close-out sweep appended every `DELIVERY_COMPLETED` at `DASH_STOP`, so every completion
    sequences AFTER every accept and no completion has a "next accept in the same dash" to pair
    with. That is exactly the `0 gaps measured` failure shape named in (c) — but on a late-sweep
    dash it is **expected by mechanism, not a fold bug**, so read that line as "no accept followed
    a completion in sequence order", not "your accepts weren't recorded". Worth a dev-eyes pass to
    decide whether the card should say so.)
  desk: needs field: compare Time-tab bars and paging with the dash; the recorded gap-fold checks cover only the data half.
  Triage source: first-line blame 2026-07-30 (`efa9b14e6`, original README L894).
