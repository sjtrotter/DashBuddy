status: active
issues: #602

- **🔧 FIX SHIPPED — a shade-tapped Accept/Decline now retries instead of dying on "No live windows" (#602).**
  A notification-action tap (heads-up Accept/Decline) can reach the click handler while a SystemUI
  takeover (notification shade, lock screen) is still covering DoorDash — the field receipt showed
  a tap landing ~16ms after the press, with the window reappearing ~0.5-1s later once the shade
  collapsed. The old code failed closed on the very first empty read (1 of 18 receiver taps died
  this way, a $13.50 near-miss). Now that one read is retried up to 3 times over delays of 300/500/700ms
  (≤1.5s total) before giving up — every other check (candidates found, label verification) is still
  single-pass, not retried. **Confirm on dash: 0/2 —** tap a heads-up Accept/Decline while the
  notification shade is open or the screen just woke: the action should still land within ~1.5s (log
  shows a DEBUG `Live window for … reappeared after a …ms retry` line), no manual tap needed. **Watch
  the control case:** a tap while DoorDash is genuinely closed/backgrounded for real (not a transient
  shade) should still fail closed with the WARN (`No live windows for package … after 3 retries …`)
  — not hang or silently succeed. Broken = a shade-tapped action failing closed despite the window
  returning within the budget, or the retry firing/looping on a genuinely-gone app.
  Triage source: first-line blame 2026-07-02 (`f47c714aa`, original README L2174).
