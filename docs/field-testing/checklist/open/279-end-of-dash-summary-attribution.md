status: active
section: §4 1/2 items older than 6 weeks / HUD
issues: #279
confirmed: 1/2

- [active] **End-of-dash summary attribution (#279).** End a dash and watch the bubble:
  the **dash summary** (total earnings / duration) should land and attribute to
  the just-ended dash — whether the summary shows BEFORE or AFTER the
  idle/offline screen (the after-idle ordering was the bug). It must NOT finalize
  as a thin "early offline" the instant the idle/offline screen appears; the rich
  total should reach the HUD.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** dash summary landed on the just-ended dash
    (totals/duration correct), not a thin early-offline finalize. One more to validate.
  Triage source: first-line blame 2026-06-02 (`cbd577b77`, original README L2894). Recorded first confirmation: 2026-06-14 (existing evidence only).
