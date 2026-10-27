status: active
issues: #597

- **🔧 FIX SHIPPED — click captures record every rule-matched tap + UNKNOWN cap re-arms after quiet (#597). READ THE PULL AFTER A DASH.**
  The week-long a11y process turned two per-process guards into forever-guards: click captures
  deduped to zero by day 3 (repeat taps hash identically), and the UNKNOWN cap (200) went blind
  for the rest of the week once hit. Now: **rule-matched** clicks (accept/decline/confirm…) are
  never deduped — every physical tap persists an envelope (UNKNOWN clicks stay bounded by the
  shared UNKNOWN budget, and now pass the sensitive-marker backstop) — and the UNKNOWN cap is
  **per burst**, re-arming after a 30-min gap with no UNKNOWN frames on that pipeline.
  **Confirm on next pull: 0/2 —** `captures/doordash/accessibility.click/` should contain
  envelopes for that day's accepts/declines/confirms (`captured=true` on the `Captured click:`
  log lines), even for buttons tapped on prior days; if an UNKNOWN-flood day happens, later
  dashes should still capture UNKNOWNs. Broken = `captured=false` on a rule-matched click, or a
  capped day staying blind on the next dash. **Calibration:** opening DoorDash casually between
  dashes resets the quiet gap (correct behavior, not a failure), and the `UNKNOWN capture cap
  re-armed` DEBUG line fires after any burst, capped or not — it alone doesn't mean the cap was
  hit.
  Triage source: first-line blame 2026-07-02 (`595549711`, original README L2231).
