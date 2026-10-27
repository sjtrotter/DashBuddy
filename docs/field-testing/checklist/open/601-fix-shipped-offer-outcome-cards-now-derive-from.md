status: active
issues: #601, #594

- **🔧 FIX SHIPPED — offer outcome cards now derive from the committed outcome, not the tap (#601).
  DELIBERATE UX CHANGE — CONFIRM ON DASH.**
  Before, tapping Accept/Decline printed "Offer Accepted"/"Offer Declined" immediately, from the
  click alone — a second, independent code path from the one that logs the outcome to the ledger
  (`resolveOfferOutcome`). They only ever agreed because the click handler happened to thread the
  same intent into both; the #594 decline-latch race showed they *can* diverge (a card claiming an
  outcome the ledger didn't record). Now a tap shows an instant **ack** only ("Accepting…" /
  "Declining…" — the #594 race warning still shows here instead, unchanged), and the real outcome
  card ("Offer Accepted" / "Offer Declined" / "Offer Timed Out!") fires later, at the resolution pop,
  off the SAME value that gets logged — card and ledger can no longer disagree by construction. A
  replaced offer (one that silently vanished under a new one) now also gets its own outcome card,
  suffixed `(offer replaced)` (e.g. "Offer Accepted (offer replaced)"), which it never got before.
  **Deliberate UX cost:** the outcome card now pops ~seconds after the tap (BK field timings put the
  resolution frame ~7-8s behind the click) instead of instantly — evaluate whether that delay feels
  acceptable on-dash. **Confirm on dash: 0/2 —** tap Accept: "Accepting…" should show instantly, then
  "Offer Accepted" when the screen actually advances past the offer. On Decline, the ack appears
  at the **confirm-sheet** tap (the first Decline tap classifies as `initial_decline` and is
  silent by design — don't score that as broken): confirm-sheet tap → "Declining…" → "Offer
  Declined" at resolution. Re-run the #594 race (decline → confirm → "Review offer" →
  Accept): the race warning ("Decline already submitted — Accept won't take") should show at tap
  time (not "Accepting…"), then "Offer Declined" at resolution. Broken = an ack that never resolves
  into a matching outcome card, or any card text that doesn't match what the db logs for that offer.
  Triage source: first-line blame 2026-07-03 (`1165556e0`, original README L2049).
