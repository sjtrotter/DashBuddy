status: active
issues: #1121, #1302, #1119

- **🆕 NEW — Compose ADD-ON recognition and honest marginal figures (#1121).**
  **On-dash:** an add-on shows and speaks once: add-on, extra pay/distance/time, no rate-based verdict,
  no net or rate; shopping opt-out, protect-stats, and merchant BLOCK preferences still apply in that
  order. A countdown redraw must not repeat speech. Decline through the sheet and timeout must return
  to the original job without an accepted add-on. **Desk:** confirm `offer_popup`, INCREMENTAL basis,
  two shop rows (95 + 32 items), one display store, null estimate columns and fuel/non-fuel split;
  explicit accept keeps gross/distance but no one-minute job ETA or partial blended rate.
  Partial paired-delta cards must stay UNKNOWN over drawer chrome. Busy-area boosts and peak-pay
  chips are not add-on sightings. Real capture intake and goldens are coordinator gates.
  - Issue: #1121. Confirmed: 0/2 (awaiting field validation)
    - desk 09-26: UNEXERCISED — no `+$` / `+N stop` text anywhere in the pull (8.98.5 or 8.99.20).
    - desk 09-27: UNEXERCISED — 0 `+$` / `+N stop` text anywhere in the pull.
    - desk 09-29: UNEXERCISED — 0 `+N stop` text; the `+$1`/`+$2` hits are the "Very busy" boost badge and idle-map
      peak-pay chips, not add-on deltas.
    - desk 10-11 (pull of 10-06→10-10, build `88dee351`, PRE-#1302 so NOT a confirmation): 4 real add-on cards on
      10-09 (`+$8.75 / +2 stops`, `+$8.20 / +2 stops`, `+$12.70 / +2 stops`); 3 were CLAIMED by `side_nav_drawer`,
      1 fell to UNKNOWN. Every card lists the already-owned order beside the new one (whole route) — so on a
      post-#1302 build watch that an explicit add-on Accept adds ONE dropoff, not one per listed row (open
      follow-up). The drawer give-up is ANY-of the delta signatures since the 10-11 follow-ups PR.
    - desk 10-18: UNEXERCISED (no `+N stop` text). A side effect of the branch-1 delta reject was found: the "Very busy"
      boost badge renders a standalone `+$1.50` node on an ordinary TOTAL card, which matches the reject's `^\+\$...$`
      pattern, so a complete card falls to UNKNOWN (10-17 15:40:13 and 17:00:35). The 15:40 offer was never recorded.
    - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): BROKEN IN PART. The 10-23 17:48:44 add-on ("+$8.00 / +2 stops", 8.104.3) was recognized as `offer_popup`, spoken once with no verdict, incremental basis. But the dasher's explicit accept was recorded `OFFER_TIMEOUT` 13 s into a 47 s countdown and the HUD said "Offer Timed Out!"; the $8.00 is missing from the dash. Evidence on #1119 (a click-less add-on accept over a task return flow fails null). Counter unchanged.
  Triage source: first-line blame 2026-09-26 (`e087c3e4a`, original README L357).
