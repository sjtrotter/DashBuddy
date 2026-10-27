status: active
section: §2 Active / 0/2 or 1/2 younger than 6 weeks — newest first
issues: #1115
confirmed: 0/2

- [active] **🆕 NEW — the offer card's Decline target must stay bound on 8.97.8.** **On-dash:** when the heads-up
  notification's **Decline** button does nothing (you have to decline in the DoorDash card yourself),
  note it. **Desk:** `bindShortfall{doordash.screen.offer_popup#declineButton}` should be 0 and
  `No 'declineButton' target bound` should not appear (09-13→15: 3 such WARNs plus a
  `offer_popup_confirm_decline … 'confirm…' resolved no node` ParseHealth line — anchor rot on 8.97.8).
  - Confirmed: 0/2
    - desk 09-20: **BROKEN, worse, and now with a mechanism** —
      `bindShortfall{offer_popup_confirm_decline#confirmDeclineButton=25}` (was 3), an earlier process
      line carrying `offer_popup#declineButton=2`, two anchor-rot `ParseHealth` WARNs, one
      `No 'declineButton' target bound`. The fielded confirm sheet
      (`offer_popup_confirm_decline/2026-09-16_18-40-14-504…83204c.json`) carries **no `Decline offer`
      node at all** — only copy lines. PR #1115 (merged `aab8d960`) re-binds the Compose target, but
      the research note `~/dashbuddy/research/compose-click-capture-2026-09-20.md` finds that a HUMAN
      tap on a Compose control emits no `TYPE_VIEW_CLICKED`, so **no click envelope can arrive to be
      classified** — hypothesis, unconfirmed on a post-#1115 build. **On the next dash this is the
      thing to watch:** does the heads-up **Decline** button work again, and does a hand-decline land
      as `OFFER_DECLINED` or as a timeout? (Detail in the 2026-09-16→19 entry, Bug 3.)
    - desk 09-21 (first pull on a post-#1115 build, DoorDash 8.98.5): **the BIND half is FIXED** —
      `bindShortfall{offer_popup#declineButton}` and
      `{offer_popup_confirm_decline#confirmDeclineButton}` are **both 0** (were 2 and 25), no
      `No 'declineButton' target bound` line, no anchor-rot `ParseHealth` WARN, 8 × `Single verified
      candidate for decline_offer on doordash … clicking it`, and 8.98.5 renders a real `Decline
      offer` node on the confirm sheet again. The **confirm** half could not be measured: the
      confirm-decline capability is un-granted on the install (12 × `Denied confirm_decline … (fail
      closed)`), so every decline needed a manual confirm tap. Still 0/2 until a dash where that
      capability is ALLOWED shows the heads-up Decline completing end-to-end.
    - desk 09-26: the BIND half holds for a second pull — 33 × "Single verified candidate for decline_offer …
      clicking it", 0 "No 'declineButton' target bound", no `offer_popup` bindShortfall. The confirm half is still
      UNMEASURED: 49 × "Denied confirm_decline … (fail closed)" — the capability is still un-granted (and the
      `1b596f74` reinstall reset the store again). 0/2.
    - desk 09-27: BIND half holds (3rd pull) — 6 × "Single verified candidate for decline_offer … clicking it" for 6
      HUD declines, 0 "No 'declineButton' target bound", no bindShortfall at all. Confirm half still UNMEASURED:
      14 × "Denied confirm_decline … (fail closed)" — the capability was not re-Allowed after the `1b596f74`
      reinstall. 0/2.
    - desk 09-29: BIND half holds (4th pull) — 21 HUD declines → 21 "Single verified candidate for decline_offer …
      clicking it", 0 unbound. One benign `bindShortfall{offer_popup_confirm_decline#confirmDeclineButton=1}` (a
      sheet render 1 s before that card's OFFER_EXPIRY). Confirm half still UNMEASURED: 46 × "Denied confirm_decline"
      — the dev has NOT re-Allowed it since the 09-26 reinstall. 0/2.
  Triage source: first-line blame 2026-09-15 (`5e1bf3416`, original README L453).
