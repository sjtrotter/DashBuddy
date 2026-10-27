status: active
issues: #501, #504

- **📸 CAPTURE NEEDED — GoPuff (Drive) screens, to finalize the #501 rules.** The 06-14 deep-dive
  enumerated the GoPuff flow (all inside the DoorDash app — there is no separate GoPuff app) from real
  captures, but three things would help finalize the rules. **On the next GoPuff dash, drop these into
  `snapshots/INBOX/`:**
  1. **The GoPuff bin/scan-steps screen** ("Pickup steps" / "Scan barcodes on N items" / "Pick up
     order in Bin #N") — confirm it always follows an explicit "Arrived at store" tap (so #501 mints
     `PICKUP_ARRIVED` from the right screen, not twice).
  2. **A GoPuff offer card** — does it ever show an **Accept** button, or is acceptance always the
     `accept_constraint_layout` control? (affects whether the Accept RuleAction can be aimed).
  3. **A 3+ order GoPuff batch's bin screen** — does every order carry a customer name
     (`order_cx_name`)? The per-drop dedupe depends on a stable per-order hash.
  - **(Low-priority curiosity, NOT a known issue):** we saw a click labeled "Open digital Red Card"
    but never captured what it opens. If it's easy, capture that screen once just to *see* what's on
    it — there is **no evidence it exposes anything sensitive**; this is only to confirm, not because
    a leak is known. (#504 was filed on a bad assumption about this and has been closed.)
  - Captured: 0/1 each (these are capture asks, not pass/fail validations).
  Triage source: first-line blame 2026-06-15 (`715060a82`, original README L2477).
