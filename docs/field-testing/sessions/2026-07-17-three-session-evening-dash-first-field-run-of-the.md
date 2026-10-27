## 2026-07-17 — three-session evening dash; first field run of the #767–#791 build (desk-analyzed 2026-07-18)

- **Date:** 2026-07-17
- **Platform(s) tested:** DoorDash (Uber enabled + ruleset loaded, but never driven — stayed Offline all night)
- **Branch under test:** `master` at `064b3fd4` (post-#790/#791 reinstall — first field data from the new sensing edge)
- **Field conditions:** 3 sessions 18:08–22:04, $60.67 reported, 5 deliveries across notably varied types: two grocery shops (H-E-B, two different locations, 24 & 35 min dwells), a pharmacy shop (CVS — Red Card + PIN-required delivery), and a restaurant 2-order single-pickup stack (Pei Wei). 7 offers → 4 accepted / 3 declined.

### Verification results (desk pass — full receipts in the 2026-07-18 desk analysis)

1. **Money path: CLEAN to the cent, all three sessions** (35.47/13.45/11.75 = reported exactly; no unattributed, no "(No session)", frozen-econ invariants hold on every row). Event stream seq 231–283 has zero ghosts/fabrications, zero ERROR lines, `restarts=0`.
2. **Migration/refold health: PASS.** In-place upgrade to Room v14 + projector v7 refold ran clean on 07-16; full history retained since 07-05 (#690 posture held).
3. **VALIDATED 2/2 → retired from the checklist this entry:**
   - **#772** PII-safe shareable.log — all 07-17 chat labels are abstract roles; zero merchant-name leaks on the new build (the raw labels still visible in shareable.log are pre-07-16 buffer).
   - **#773** address running-keys — two NEW H-E-B locations keyed `@9255`/`@5601` on sight; `stores` now holds six distinct H-E-Bs; CVS/Pei Wei keyed by receipt code. Clean split on a second independent dash.
   - **#688B** per-leg mileage — first LIVE stacked job carried both legs correctly (drop A 0.745+4.508, drop B shared-store claim-once null+3.103), Σ 8.356 ≤ span 8.36 boundary-exact.
   - **#438 B4** (desk half) — three more decline `(offer=<hash>)` lines exact-match their `offer_records`.
4. **Advanced to 1/2:** **#788** (10 clean verified-candidate taps — 3×decline_offer, 3×confirm_decline, 4×expand_earnings — zero tie-aborts; the window-root dedup collapsed the twin upstream, so no "Dropped other-window candidate" line was even needed); **#435** pre-map skip (negative half: 350+ frames forwarded, `mappingFailures=0`, every delivery recognized — see checklist-item note on the VERBOSE caveat); **#630** (Pei Wei stack split 6.72+6.73 exactly, no tripwire); **#159** (all stores keyed with real running keys).
5. **Reinforced (no advance):** #438 B3/B5, #701 (all sessions reconcile → no false callout), #315 H5 (dwell minutes-scale sane), #588 (shop rate 0.68→0.72/min, n=6→8, `[doordash]`-tagged), #733 (0 join-miss WARNs — but no multi-pickup job fielded, hard case still open). **#731: NLS connects=1/disconnects=0 across the night — third clean night; stays parked.**
6. **Not exercised (items stay put):** #749 same-customer double, #736/#752 unassign, #660p2 orphan, #691 receipt-less shop, #778 shop-decline (shopping was ON), #728/#722 dev-eyes halves, and **every Uber item** (D2 skeleton, coarse-close WATCH, #785/#786 captures).

### Bugs

1. **[MED-HIGH, privacy — filed #794]** DasherDirect **"Transfer out"** screen variant evaded BOTH the sensitive rule block and the `SensitiveTextMarkers` backstop → plaintext balance ("$X.XX available", amount masked here) written to two UNKNOWN debug captures. Copy drift is the likely cause (screen says "Transfer out"/"$X available"; markers say "Transfer to bank"/"Available Balance"). ~44 sibling DasherDirect frames in the same window WERE blocked — single-variant coverage gap. Debug-only exposure (release = NoOpCaptureBus), never forwarded to the state machine.
2. **[MED, recognition — filed #795]** CVS **PIN-required delivery** confirmation sub-flow entirely UNKNOWN (~6 frames: "Enter PIN…" + intro). Delivery completed correctly regardless; a whole confirmation surface is invisible to recognition. The PIN itself must be treated as do-not-parse when the rule is written.
3. **[LOW-MED, recognition — filed #796]** Batch of UNKNOWN families: drop-off issue/resolution menu, "How was this task?" feedback, pickup receipt-photo, CVS barcode-scan shopping sub-flow (#550-class), CVS "Navigate to zone".
4. **[LOW — noted on #501]** One `dropoff_multi_order_confirm` variant ("Confirm you have the correct order before drop-off.") slipped to UNKNOWN while its sibling recognized.
5. **[LOW — noted on #785]** Lone Uber capture of the night is an unrecognized earnings-promo push — feeds the #785 hazard list (promo/marketing class).

### Open questions / investigations

1. CVS drop (seq 282) used the legacy partition-delta mileage fallback because `DELIVERY_ARRIVED` never fired (dash paused mid-drop, seq 279/281). Documented expected fallback — worth one more sighting to confirm the pause interplay stays benign.
