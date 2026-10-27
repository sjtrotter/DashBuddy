status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / HUD
issues: #458, #1
confirmed: 0/2

- [review] **No transient double drop-off card at the door (#458).**
  On an arrival-bearing dropoff (hand-it-to-customer / photo / PIN) the same delivery briefly
  rendered as TWO cards during the at-door window (a frozen completed copy + the live one). The
  stack now drops the frozen twin when it shares the active card's id. On a dash: working = at
  the customer's door you see exactly ONE delivery card (then the single frozen card + the live
  "Saved" receipt after you complete). Broken = two identical delivery cards stacked at the door.
  - Confirmed: 0/2. **2026-06-14 (DoorDash):** an extra drop-off card DID appear this dash — but it's the
    **premature/unsettled-frame** class (2026-06-13 #1), *not* the frozen-twin overlap this #458 fix
    targets, so this stays 0/2 (the frozen-twin case wasn't disambiguated). The recurrence is tracked
    under 2026-06-13 #1 / the ghost-frame watch — pull the dropoff capture to tell the two apart.
  desk: needs field: watch the at-door transition for a transient frozen/live duplicate; final DB rows cannot establish card overlap.
  Triage source: first-line blame 2026-06-13 (`09a4a57b8`, original README L2627).
