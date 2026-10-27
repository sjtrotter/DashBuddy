status: must-watch
issues: #1058, #1123, #1122, #1116, #985

- **🆕 NEW — #1058 — the two dropoff sheets that were shipping addresses and door codes to
  UNKNOWN captures are now recognized and redacted.** Leak A is the ALCOHOL variant of the drop-off
  arrival card (the one that asks you to scan an ID and collect a signature): it renders no
  "Delivery for" line, so no rule matched it and the unit number + the customer's instruction body
  went to disk raw. Leak B is DoorDash 8.93.7's id-less "Leave it at the door" workflow sheet
  (Call · Message · Directions · Continue), which shipped the street line, city/ST/ZIP, the unit,
  the quoted customer note (with a code in it) and a bare 3-digit code. Both now have a rule with a
  full redact; the two node ids also joined the UNKNOWN-path id backstop.
  **On-dash:** nothing to watch — the fix is capture-side only. The one thing to NOTICE is that
  nothing changed in behaviour: an alcohol drop-off and an ordinary "leave it at the door" drop-off
  must still narrate, bubble and complete exactly as before (the new sheet rule declares no flow on
  purpose).
  **Desk, after the pull:**
  1. `grep -rl 'drop_off_workflow_host_fragment\|alcohol_dropoff_ic_scan' captures/**/UNKNOWN/`
     must return **nothing** — a hit means the frame still fell UNKNOWN and the rule missed it.
     (Require a co-present TEXT node: text-free `drop_off_workflow_host_fragment` loading skeletons are a
     known false positive, seen on both the 09-07 and 09-09 pulls.)
  2. In the recognized folders for those two surfaces
     (`captures/doordash/accessibility.window/dropoff_pre_arrival/` and
     `.../dropoff_workflow_sheet/`), every address line, unit/`Apt` value, instruction body, quoted
     note and bare code must read `[redacted]` or `[redacted:<4hex>]` — the codes, unit numbers,
     ZIPs and notes must be **plain** `[redacted]` (no hex), the street lines and any bare
     first-name + last-initial keep the hex.
  3. `grep -c dropoff_workflow_sheet app.log` — a non-zero count on a dash where you took a
     leave-at-door drop is the positive signal that the new rule is live on the device build.
  - Confirmed: 0/2 (desk 09-07 on `aabb56d0`: NOT EXERCISED — neither the alcohol arrival card nor the leave-at-door workflow sheet rendered (`grep -c dropoff_workflow_sheet` = 0). Adjacent positives: ordinary `dropoff_pre_arrival` frames on 8.95.6 are fully masked with the right GRADES — `Deliver to [redacted:7201]` + a hashed street line + PLAIN `[redacted]` for the unit and the customer note. Check 1's grep returns two FALSE positives on text-free `drop_off_workflow_host_fragment` loading skeletons — require a co-present text node. Desk 09-09 on `8028691a`: NOT EXERCISED again — `grep -c dropoff_workflow_sheet` = 0, three more text-free skeleton false positives; adjacent positive holds on 8.96.8: all six `dropoff_pre_arrival` envelopes read `Deliver to [redacted:<hex>]` + hashed street + PLAIN `[redacted]` for unit, ZIP, note and an `Entry code` block.)
    - desk 09-13 (3rd pull): NOT EXERCISED — `grep -c dropoff_workflow_sheet` = 0; check 1's two hits are the documented text-free skeletons; all 12 `dropoff_pre_arrival` envelopes on 8.96.8 mask correctly.
    - desk 09-15 (4th pull): NOT EXERCISED for the workflow sheet. NEW on 8.97.8: the alcohol pre-arrival card DID render (`UNKNOWN/2026-09-14_12-57-05-769…`, `alcohol_dropoff_ic_scan`) and the #1058 arm did NOT claim it — no PII on that render, but the arm needs re-checking against 8.97.8. The `dropoff_pre_arrival` adjacent positive holds on all 34 envelopes.
    - desk 09-20 (5th pull): **LEAK B's workflow sheet EXERCISED for the first time, and CLEAN** — `grep -c dropoff_workflow_sheet` = 2, and the single envelope (09-17 10:59) grades correctly: `[redacted:de0f]` for the name, PLAIN `[redacted]` ×5 for the address lines / unit / note, `Leave it at the door` and the merchant item descriptions kept. All 34 `dropoff_pre_arrival` envelopes still mask correctly. Check 1 returns 7 UNKNOWN hits: 6 are the documented text-free skeletons and the 7th is a NEW unruled handoff-photo instruction page (`2026-09-18_15-36-07-896…0bf650`, no PII). The item stays open for **leak A** (the alcohol arrival card did not render this window).
    - desk 09-21 (6th pull): **PARTIAL PASS WITH A REGRESSION.** The two `dropoff_workflow_sheet`
      envelopes still grade correctly on 8.98.5 (hashed name, PLAIN `[redacted]` for the address
      lines / unit / note). But the adjacent positive that has carried this item for five pulls
      BROKE: one `dropoff_pre_arrival` envelope shipped the bottom-bar customer name **raw**
      (#1123 — the rule's redact does not cover that node, while `dropoff_workflow_sheet`'s does),
      and the same sheet's un-anchored SECOND frame fell to UNKNOWN carrying everything (#1122, the
      #1116/#985 class recurring). Leak A (the alcohol arrival card) still has not rendered.
    - desk 09-26: PARTIAL — the alcohol pre-arrival card (`alcohol_dropoff_ic_scan`) is claimed as
      `dropoff_pre_arrival` 4× with no raw PII (09-21 12:39/12:52, 09-23 09:04/09:19). The workflow sheet was
      recognized and masked once, but its header-less frame leaked again (#1122; fix not on the phone).
    - desk 09-27: NOT EXERCISED for either leak surface — 0 `alcohol_dropoff_ic_scan`, 0 `dropoff_workflow_sheet`.
      Adjacent positive holds: 14 `dropoff_pre_arrival` envelopes grade correctly.
    - desk 09-29: NOT EXERCISED — 0 `alcohol_dropoff_ic_scan`, 0 `dropoff_workflow_sheet`. Adjacent positive: 11
      `dropoff_pre_arrival` grade correctly.
    - desk 10-18 (8.102.6): leak A EXERCISED on three alcohol drops: the arrival card was claimed as `dropoff_pre_arrival`
      10 times, but 3 header-less scrolled frames (10-16 14:31:13, 14:31:35, 14:31:36) fell to UNKNOWN, so check 1 does not
      pass. Those 3 frames carry no name or address (instruction body `[redacted]`; order number and item list raw, as on
      the recognized frames). The workflow sheet rendered once (10-14 14:26:16) and was recognized; 27 recognized
      `dropoff_pre_arrival` envelopes grade correctly. Counter unchanged.
    - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): NOT EXERCISED. The only UNKNOWN hits are text-free `drop_off_workflow_host_fragment` skeletons (10-20 ×2). Counter unchanged.
  Why proposed: Redaction: dropoff addresses and door codes must remain masked across the two sheet shapes.
  Triage source: first-line blame 2026-09-05 (`054433e69`, original README L716).
