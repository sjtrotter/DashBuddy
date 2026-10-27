## 2026-07-19 afternoon/evening — four sessions incl. the FIRST Uber attempt (desk-analyzed 2026-07-20)

- **Date:** 2026-07-19 (13:10–19:20; the morning session was logged in the previous entry)
- **Platform(s) tested:** DoorDash + **Uber (first real drive attempt — brief, concurrent with DoorDash, messy)**
- **Branch under test:** still the 2026-07-17 install (#767–#791)
- **Field conditions:** 4 sessions: Uber 13:10–13:32 ($0, one offer timed out), DoorDash $42.19 (3 del) + $85.82 (3 del) + a $0 early-offline. All 6 deliveries single-drop H-E-B shop orders with receipts. One mid-session chat-driven unassign (the $28.50 60-item shop).

### Verification results (desk pass, receipts in the 2026-07-20 analysis)

1. **Money path: exact again** ($42.19 and $85.82 both reconcile to the cent; independent corroboration from the recognized `earnings_deposit` notifications naming exactly the three moneyed sessions of the day). Zero orphans/over-attribution; frozen-econ invariants exact; 0 ERROR, 0 restarts, 0 NLS disconnects; projector v7 caught up.
2. **#788 held under load** (17+ automation taps incl. one Uber decline; zero tie-aborts; one CORRECT fail-closed abort — see Bug #3). **#773 keyed 3 more H-E-Bs by street number.** Per-leg mileage Σ = odometer span exactly on both moneyed sessions.
3. **Uber D2 (#762/#784): attempted but INCONCLUSIVE.** The Uber app itself was unstable (repeated splash/restarts). The one recognized offer (54th St Grill, $8.01/32mi, −$2.28/hr — correctly unappealing) OFFER_TIMEOUT'd cleanly. A Cheba Hut trip was accepted DURING a restart gap — DashBuddy never saw the offer screen, so no job was minted and the D2 accept-consumption path never ran. Capture wins: first real `trip_en_route_pickup` notification envelopes, active_trip frames, splash corpus (#785 fodder). **#786 field-confirmed still open:** `No 'declineButton' target bound for uber` WARN fired.
4. **Not exercised:** #630, #733, #749, #752 (dropoff-phase), #691, #660p2, #778.

### Bugs

1. **[MEDIUM, state — filed #810]** The chat/resolution-path pickup unassign fired NO `TASK_UNASSIGNED` — #736's shipped vocabulary keys on the confirmation screen, which this path never rendered. The next accept **silently reused the abandoned job/task** (same taskId across both accepts); money reconciled only by same-store luck, and the orphaned accept inflates offersAccepted. Second #736 commit shape; replay captures complete (session 114).
   - **Status:** Partially closed — design pass 2026-07-20 on #810 proved the unassign committed via the support-chat path with NO capturable dasher-side surface (recognition can't be the load-bearing fix) and REJECTED the accept-time store-differs guard. Shipped: B1 `JOB_ACCEPT_MISMATCH` close tripwire (#818, replay-pinned on this session's shape) + B3 issue-list variant recognition/redact (#820). Open: B2 orphan offer_record resolution (dev decision, on #810) + #816 (timeline owed-set reconciliation, capture-first).
2. **[MEDIUM, privacy — extended #806]** "Current task" bottom-sheet fell to UNKNOWN carrying plaintext `Pickup for <FirstName> <I>` — "Pickup for " is missing from the `CustomerTextMarkers` SSOT (which has "Deliver to "). Same id-less UNKNOWN class #806 owns; marker addition + sheet recognition both requested there. File purged.
   - **Status:** Partially closed — "Pickup for " marker + the full UNKNOWN screen/notification/click scrub shipped in #815 (2026-07-20); the sheet/task-detail recognition (direction 1) stays open on #806, capture-gated.
3. **[LOW, actuation — filed #811]** One confirm_decline tap aborted fail-closed on a label variant (`NONE passed label verification`) — the #770 "copy change degrades quick-decline silently to manual" residual, first field sighting. Fail direction correct; allowlist needs the variant.
   - **Status:** Premise REFUTED (2026-07-20 investigation, receipts on #811) — no label variant exists; the WARN was the second fire of a benign teardown double-fire and the offer WAS auto-declined (13:38:12.391 `Offer Declined`). Quick-decline never degraded. Disposition (close as not-a-bug vs re-scope to a teardown-frame effect dedup) pending dev decision on #811.
4. **[INFO]** Uber offer capture is fragile during Uber-app instability (the Cheba Hut accept escaped entirely) — can't be separated from app-restart churn this session; watch on the next (hopefully stabler) Uber dash.

### Open questions / investigations

1. Whether the #810 fix should ride the offer-supersession guard (the #596 T2 accept-time cousin) or a resolution-menu recognition signal — design pass on the issue.
   - **Answered (design pass 2026-07-20, on #810):** NEITHER as the load-bearing fix — the fielded unassign had no capturable commit surface (support-chat path), and the accept-time store-differs guard fails both directions (misses same-store, misfires on genuine cross-store add-ons; rejected permanently). Shipped instead: the B1 close-reconciliation tripwire (#818); the platform-authoritative general fix is #816 (timeline owed-set reconciliation, capture-first).
