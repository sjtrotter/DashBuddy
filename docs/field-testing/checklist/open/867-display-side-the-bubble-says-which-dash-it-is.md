status: active
section: §4 1/2 items older than 6 weeks / HUD
issues: #867, #873
confirmed: 1/2

- [active] **🆕 NEW — #867 (display side) — the bubble says WHICH dash it is showing, and you can switch it.**
  With two platforms online the HUD used to follow whichever app produced the last frame, unlabeled
  — a DoorDash "Declined" chip read as the Uber offer you had just acted on. Now: platform chips on
  every card + the chat header whenever ≥2 dashes are live, a manual **Showing DD | UBER** chip row
  above the stack, and a Dev-settings switch (Settings → Developer Options → **Session
  presentation**) picking between **Follow** (default), **Pin**, and **Merge**.
  **What to watch (needs a genuine multi-app dash — both apps online at once):**
  1. **Chips appear** the moment the second dash starts (cards + chat header), and DISAPPEAR when
     you drop back to one platform. A chip must never name the wrong app.
  2. **Follow (default):** the stack tracks whichever app you last touched. Tap the other chip in
     the switcher — the stack + chat swap to that dash and STAY there while you use the other app.
     The live (bottom, expanded) card disappears while you're looking at the non-active platform —
     that's intentional (there is no live screen for it), and the Accept/Decline buttons go with it.
     End the dash you pinned → the HUD goes back to following on its own.
  3. **Pin:** same, except the pick survives that dash ending and re-takes the surface when that
     platform dashes again. Nothing is persisted across an app restart — a pin is per-session.
  4. **Merge:** one stack with both dashes' cards interleaved chronologically, each chipped; the
     switcher row is hidden and chat stays on the followed dash. This is the mode to judge — it's
     the one the dev was unsure about.
  **Which mode felt right while driving** is the actual deliverable — the winner becomes the
  shipped default and the other two get deleted.
  **Desk:** the two dashes' chat histories should now be cleanly separated per session (the #873
  write-side fix); confirm no line from one platform sits in the other's `chat_messages` rows.
  - Confirmed: 1/2 (desk 07-30, the 07-29 dash — the DESK/write-side half only: `chat_messages`
    groups cleanly per session (49 / 2 / 8 rows across the three sessions, zero cross-session
    bleed). Single-platform evening, so the display half — chips, switcher, the three modes —
    remains untested; the second confirmation needs a genuine multi-app dash with dev eyes.)
  Triage source: first-line blame 2026-07-27 (`9a41c99b5`, original README L1172). Recorded first confirmation: 2026-07-29 dash, desk 2026-07-30 (write-side only) (existing evidence only).
