## 2026-11-12 (desk analysis of the 11-12 pull: dashes 11-07 and 11-11; money reconciles; two trailing-plus cards lost; v0.233.0's offer pool would likely tie most accepts on shifts like these)

- **Date:** 2026-11-12 (pull). Dashes on 11-07 (one shift) and 11-11 (three shifts), DoorDash 8.106.9; a two-minute Uber browse on 11-07 before the dash.
- **Platform(s) tested:** DoorDash.
- **Branch under test:** `0.230.0+9064004e.dirty` (PR #1536 merge) on 11-07; `0.231.0+96fa4a89.dirty` (GitHub's merge ref of PR #1592 onto `master` at `dfb9a52eb`, post-#1589) on 11-11. Not on the phone: #1593–#1596, #1603, #1605, #1607, #1608 (released as v0.233.0).
- **Field conditions:** four shifts ended on the summary screen: 11-07 $105.21 (11 offers, 4 accepted, 5 deliveries, 59 mi); 11-11 $39.50, $44.50 and $27.43. All ten jobs and 22 of 26 offers were shop orders at one grocery chain (store A). Two stacked jobs, one alcohol delivery, no cash tips.

### Bugs

1. **Two trailing-plus offer cards were never recorded (#1604).** A full `$27.35+` card on 11-11 fell to UNKNOWN; it was a decline, so DoorDash counted 12 offers and the app 11. A `$12.15+` card at 14:51 was accepted: the job ran with no offer behind it and its $19.15 receipt priced the drop, while DoorDash counted 3 of 4 accepted and the app 2 of 3. Both cards carried a "High pay for Gold" badge.
   - **Status:** Shipped in #1607 (2026-11-12), after this build.
2. **Two-order shop offers read as quick pickups (#1528).** Both cards ("Shop for 2 orders (20 items, …)", "Multiple dropoffs (2 stops)") stored 0 items and no shop. They were estimated at 39 and 34 min; the jobs took about 60 and 57 min. One card's row reads "(20 items, 26 units)" with no "~T min". The #1595 pattern appears to match it, but the PR's test covers only the "~T min" wording.
   - **Status:** Shipped in #1595 (2026-11-11), after this build. The "units" wording is untested.
3. **A drop was committed early when the dasher left the drop page, and its drive miles went missing (#1611).**
   - On 11-07 the dasher answered DoorDash's "leave this page?" prompt mid-drop and landed on "Finding offers". About 10 s later a grace committed the drop (`DELIVERY_CONFIRMED`, and the `#1458 … left open … retired` DEBUG line). The dasher came back and finished the drop 12 s later.
   - The row's drop leg reads 0.0 mi, although GPS logged several miles on that leg. The shift's drop rows are 5.9 mi short of its span.
   - On 11-11 a `navigation_generic` frame mid-drive did the same, 4.5 min before arrival.
   - Pay was right both times. Likely cause: a non-drop frame held for the grace retires the drop, and the revived drop measures its leg from the revival. Would need a replay of the 11-07 frames to confirm.
   - **Status:** Open.
4. **A census batch was rejected as bad_request again (#1530).** 28 items at 11-07 10:33:57, about 3.5 min after the Uber browse, the same shape as 11-04. This supports the hypothesis that an Uber skeleton fails server validation.
   - **Status:** Open.

### Open questions / investigations

1. **v0.233.0's offer pool (#1605) would probably tie (#1610) most of these accepts.** This is a desk prediction from the merged rules, not observed.
   - Every accept and decline in this pull was inferred, with no click envelope. In v0.233.0 an accept links only when exactly one waiting offer is at the store AND no same-store decline left in the last 60 s.
   - An offer that expires waits all shift, and tied offers stay candidates for the rest of the shift.
   - Replaying those rules over the recorded times: on 11-07 the 10:50 expired offer ties all four jobs. Two of those would also tie through same-store declines 28 s and 51 s before the accept. On 11-11 one job ties through a decline 56 s earlier.
   - Projected: 11-07 would place $0.00 of $105.21 automatically and record 0 of 11 accepts. 11-11's first shift would place $24.30 of $39.50.
   - The receipts are saved and matchable by hand (#1599); the offer links wait on #1606.
   - A session replay of the 11-07 shift through v0.233.0 would confirm or refute this before the next one-chain grocery shift.
2. **Dash Along the Way skipped the receipt again.** A two-drop job went photo → Dash Along the Way with no receipt sheet and was priced from the offer. This is another data point for the #1119 note.
3. **The receipt auto-expand fired five times in 16 s on one receipt** (11-07). It re-fires each time DoorDash shows the collapsed receipt again. It failed closed once; no harm seen. Is one tap per receipt the intent?
4. **Pickup captures keep the order-number title.** On 8.106.9 the pickup sheet's `instructions_title` renders the store's order number, and captures keep it raw. It is not a name or address; whether it counts as customer-linkable is the dev's call.
5. **Shift 3's end odometer reading** is 4 mi over the shift, against 6.5 mi on GPS and DoorDash's own 6.7 mi route quote. Possibly a typed reading (#1354: a typed reading cannot be corrected).

### Field UX context

- When the countdown ends on DoorDash's decline sheet, the sheet shows "Declined" and "0:00" where "Decline offer" was.
- The two-order shop row comes in two wordings: "(N items, ~T min)" and "(N items, M units)".
- DoorDash zone-promo pushes come in variants the rules do not know: "Extra $N to shop", "Peak Pay: $N more per delivery", "Busy in <zone>", "Peak Pay in <zone>", "New Peak Pay in your area" (12 in this pull, all UNKNOWN).
- Leaving the drop page asks "Are you sure you want to leave this page?" and lands on "Finding offers" with the job still open.

### Verification TODOs

- **Money:** all four shifts reconcile to the cent. Only one receipt was split across drops: a one-offer two-drop stack, evenly, summing to the receipt. No pipeline, state machine or snapshot errors; no restarts.
- **PII sweep:** 266 UNKNOWN captures, 0 customer PII. Banking sweep 0 hits. The ID-scanner frames were dropped by the sensitive gate. The 5 backstop-scrubbed envelopes are masked.
- **Census:** device 132 uploaded, 1 batch rejected. Server: one trusted install, health rows for every dash day, 138 clusters on 8.106.9, none classified.
- **Checklist** (desk evidence, no count changes):
  - #1058: both leaks exercised and masked.
  - #919: clean.
  - #1458: data half consistent (both stacks committed about 10 s after the last photo).
  - #1310: 12 of 12 rows.
  - #1108: Σ ≤ span on all four (see bug 3).
  - #1533 and #1535: data halves landed on the stuck 11-05 shift.
  - #1560: no false trip on three shifts.
  - #1396/#1397: hold.
  - #1528 and #1604: broken as expected on pre-fix builds.
  - #1343, #1312, #1527 as written, #1002 and #1459: not exercised.
