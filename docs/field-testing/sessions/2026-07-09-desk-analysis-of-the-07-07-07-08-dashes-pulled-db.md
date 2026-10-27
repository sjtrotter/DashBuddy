## 2026-07-09 — desk analysis of the 07-07 & 07-08 dashes (pulled db/logs/captures; no in-field narration)

- **Date:** 2026-07-09 (dashes analyzed: 07-07 afternoon/evening — 3 sessions; 07-08 evening — 3 sessions)
- **Platform(s) tested:** DoorDash
- **Branch under test:** 07-07 = `master` post-#726 (mode-adaptive gas build); 07-08 =
  `fix/722-693-idle-gas-reachability` build (same + the idle-card reachability fix, merged as PR #729)
- **Field conditions:** dev reported noticing nothing anomalous either dash; this entry is pure desk
  synthesis of `~/dashbuddy/logs/2026/07/08` + `07/09` (db+WAL, logs, ~900 captures)

### Bugs (all filed 07-09)

1. **#731 — NotificationListener flapping.** 129 disconnect/reconnect cycles on 07-07, 240 on
   07-08; bursts to 6/min. Each rebind is a notification-miss window.
   - **Status:** Open (filed #731).
2. **#732 — graced-commit seq/occurredAt inversion, 2nd sighting.** 07-07 seq 70/71 (DASH_STOP
   appended before an earlier-occurredAt PICKUP_CONFIRMED); 07-08 seq 116/117 (Willie's
   PICKUP_CONFIRMED occurredAt 19:24 appended after Sonic's 19:35 PICKUP_ARRIVED — an 11-min
   inversion from the pickup-confirm grace). Not corrupting today (folds are seq-ordered), a
   latent event-sourcing fidelity trap.
   - **Status:** Shipped in #769 (2026-07-12) — Option B (dev-decided): the invariant is documented
     at the `AppEventEntity` contract (sequenceId authoritative; the sole inversion carrier is
     PICKUP_CONFIRMED via the grace-armed `Task.completedAt`, appended at the close-out sweep),
     consumers audited (all sequenceId-ordered or insensitive), and a characterization test pins the
     shape so a silent re-stamp trips it. No behavior change.
3. **#733 — D6 join miss → NULL-store delivery (TOP finding).** The Sonic Drive-In + Willie's
   Grill & Icehouse two-pickup single-customer job ($19.50): the dropoff's customer hash matched
   **0 of 2** pickup-lineage hashes ("possible pickup/dropoff hash-format drift" per the WARN,
   ×23) → `delivery_records.storeName = NULL` → the $19.50 buckets under "Unknown store" in every
   per-store view. Replay captures on disk (`dropoff_pre_arrival` 19:42:44 + both pickups).
   - **Status:** Open (filed #733).
4. **#734 — actuation 2-candidate label ties.** `confirm_decline` (×4) and `expand_earnings` (×3)
   across the two dashes resolved 2 verified candidates and clicked the first. No observed
   misfire, but a tie should abort to manual per the #425 posture.
   - **Status:** Shipped in #770 (2026-07-12) — bindings tightened from the corpus (the old
     `expandButton` bind was decisively tapping the WRONG node — the stats section) and an
     unresolved tie now aborts to manual per the #425 posture. Checklist item above.

### Validations (data-side)

5. **#438 B1 retired here — 2/2.** Both dashes: accepts minted jobs with economics (every
   delivery costed `OFFER_FROZEN`), exactly one `DELIVERY_COMPLETED` per drop, sessions ended
   normally. Single-platform behavior byte-identical at the event-log level.
6. **#691 OFFER_PAY estimate — first live firing, correct.** 07-08 receipt-less H-E-B shop folded
   `payBasis OFFER_PAY` at $14.25; session reconciled exactly.
7. **Reconciliation exact on all 5 moneyed sessions** across both dashes (Σ delivered = reported:
   $104.47 / $32.24 / $14.25 / $19.50; two $0 sessions clean) — no false #701 callout possible.
8. **#605 MODE_RESUME_COMMIT** debounced a real pause cleanly (07-08 16:25 DASH_PAUSED).
9. **Pipeline health:** 0 restarts, 0 mapping failures, sensitive gate dropped 23–24 frames/dash,
   UNKNOWN captured for triage (163 on 07-08 — ~65 window frames to sort).

### Open questions / investigations

10. **The 07-07 15:43 $21.75 H-E-B order — RESOLVED (dev-answered + capture-verified): it was
    UNASSIGNED via the help workflow, and the logged `PICKUP_CONFIRMED` is FABRICATED.** The
    captures show the full flow: `arrived_at_store` 15:21 → shopping → `pickup_issue_menu` /
    `pickup_resolution_options` (recognized) → support `chat_conversation` 15:24–15:26 → the
    **unassign confirmation surfaces 15:43:03–15:43:17, all UNKNOWN** ("Unassign"/"Wait" tokens,
    two UNKNOWN clicks) → `dash_along_the_way` 15:43:18 — which the state machine recorded as
    `PICKUP_CONFIRMED` (fake 21.5-min `shopping` dwell). No stranded-order gap; instead a
    state-vocabulary gap: unassignment has no representation, the exit fabricates a confirm, and
    a continued dash would carry a ghost job (#596 shape) — plus fake dwell into #159's future
    `pickup_records`. Same flow on 07-05 `320-15` (2nd occurrence, no fabricated confirm there —
    unassigned pre-arrival).
    - **Status:** Open (filed #736 — recognition rules for the unassign surfaces + an enumerated
      unassign/abandon event through `StateMachineContract`; cross-linked #732, the seq-70/71
      inversion instance being this very event).
