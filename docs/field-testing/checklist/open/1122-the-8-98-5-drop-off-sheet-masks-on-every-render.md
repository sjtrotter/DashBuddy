status: active
section: §2 Active / 0/2 or 1/2 younger than 6 weeks — newest first
issues: #1122, #1123, #1125
confirmed: 1/2

- [active] **🆕 NEW — the 8.98.5 drop-off sheet masks on every render that keeps a stable row (#1122 + #1123).** The 09-20 pull
  shipped two Pledge leaks from the same sheet: a raw customer name in the bottom bar of a
  `dropoff_pre_arrival` envelope (the id-less node the rule's id-anchored entry never saw — #1123),
  and three header-less / partial renders of the workflow sheet that fell UNKNOWN with the whole
  address block, the quoted note and the name raw (#1122). Nothing changes on the phone: the sheet
  rule still declares no flow, so an ordinary "leave it at the door" drop-off must narrate, bubble
  and complete exactly as before. Anchors: either prism CTA, any `Apt/Suite` spelling, the
  Call/Message pair, or `Leave it at the door` / `Hand it to recipient` — a render with NONE of
  those still ships raw (extend the vocabulary from the capture's node shape). **Desk, after the pull:**
  1. `grep -rl 'drop_off_workflow_host_fragment' captures/**/UNKNOWN/` must return only the known
     text-free loading skeletons — any hit with a co-present text node is a render the widened anchor
     still misses (attach the frame's node SHAPES to #1122, never the values).
  2. Every `dropoff_pre_arrival` AND `dropoff_workflow_sheet` envelope: the bottom-bar name slot
     (the id-less `TextView` between the `Settings` and `Safety` icons) reads `[redacted:<4hex>]`,
     and the SAME customer carries the same 4 hex on both rules' envelopes.
  3. On the header-less sheet envelopes (no `Deliver to`, no `Continue`): a hashed street line, PLAIN
     `[redacted]` for city/ST/ZIP, the unit and the quoted note; `Call`/`Message`/`Directions`/
     `Leave it at the door` kept raw.
  - Confirmed: 1/2 (desk 2026-10-05: check 1 passes)
    - desk 09-26: UNEXERCISED — build `71bacc8c` predates PR #1125. The #1122 shape recurred once on 8.98.5
      (UNKNOWN 09-23 11:30:13: street, city/ZIP, unit, quoted note + gate code, bare code and the bottom-bar name raw;
      purged). 9 other UNKNOWN host-fragment hits are text-free skeletons. The recognized `dropoff_workflow_sheet`
      envelope (09-23 11:38:20) masks everything incl. a hashed bottom-bar slot; no `dropoff_pre_arrival` rendered the
      bottom bar. The phone is on `1b596f74` now, so the next pull is the first that can measure this.
    - desk 09-27 (first run of PR #1125, `1b596f74`, DoorDash 8.99.20): CLEAN, not a confirmation. Check 1 passes (4
      UNKNOWN host-fragment hits, all text-free loading skeletons 0.3–0.6 s before a recognized `dropoff_pre_arrival`).
      Checks 2–3 UNEXERCISED: on all 14 `dropoff_pre_arrival` + 2 `dropoff_handoff` envelopes the bottom-bar slot
      between Settings and Safety is an empty View (no name rendered); 0 `dropoff_workflow_sheet` frames, no
      header-less render. Every pre-arrival grades correctly (same hex per customer across frames). No
      `Apt/Suite`-only UNKNOWN; 0 `Building Name`. Still 0/2.
    - desk 09-29 (2nd run of PR #1125, `1b596f74`, two dash days): CLEAN, not a confirmation. Check 1: 0 UNKNOWN
      host-fragment hits. Checks 2–3 UNEXERCISED again: on all 11 `dropoff_pre_arrival` + 4 `dropoff_handoff` envelopes
      the Settings→Safety slot is a Button + two text-free Views (no name rendered); 0 `dropoff_workflow_sheet`; no
      header-less render. Every Deliver-to hex equals that drop's customerHash prefix (8 customers). Still 0/2.
  Triage source: first-line blame 2026-09-21 (`81d0f5531`, original README L323).
