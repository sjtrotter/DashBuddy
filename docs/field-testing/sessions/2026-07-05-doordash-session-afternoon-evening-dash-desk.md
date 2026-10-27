## 2026-07-05 — DoorDash session (afternoon/evening dash; desk synthesis 07-06 from db/logs/captures)

- **Platform(s) tested:** DoorDash
- **Branch under test:** `master` @ `a9a47e08` (post-#679 — the full 07-05 evening wave through P7 logging phase 1; pre-#680..#686)
- **Field conditions:** ~4.5h afternoon/evening; 8 offers (5 accepted / 3 declined), 5 deliveries — three big H-E-B shops + one two-pickup stack (Bill Miller BBQ + Mama Margies); one final shop accepted then manually unassigned. Reported gross $104.47. Data pulled 07-06 to `~/dashbuddy/logs/2026/07/06/` (46k log lines, 464 captures, db integrity ok). NOTE: app data was manually cleared before the dash, so the on-device event log starts at this session (07-03 backup preserves older history; hardening → #690).

### Bugs

1. **Receipt/pay capture gap — 3 of 5 drops completed with NO pay signal** (payBasis NONE): the first solo H-E-B shop AND both stack drops. $39.45 of the reported $104.47 landed as unattributed until hand-corrected. Hypotheses + triage plan (the missing receipt frames are likely among the 141 UNKNOWN captures) → **#691**. The stack's two completions minted at the same instant (16:08:19) with the Bill Millers drop carrying no store name — the #526/#557 family; the receipt was never captured at all, so the #528 apportioner had nothing to split.
2. **Post-dash bubble showed a STALE dash** — after the $0 unassign session, the bubble fell back to the last dash *with money* instead of the actual last session. Direction (dev): the post-dash bubble should render a last-dash SUMMARY (design handoff) + just-in-time actions (vehicle, gas price) → **#693**.
3. **CSV summary claims tax year 2025 / $0.70** for 2026 driving (found via the export) → **#689** (2026 = $0.725, per-year lookup; Time tab card is a second consumer; unknown-year = latest-known + disclaimer, decided).

### Field UX context

4. **Corrections (#650 PR B) carried the session's accounting.** Adjustment trail (log events 57–61): H-E-B → 26.50; the empty-store stack drop → 11.375 → 7.60 (two tries), re-saved with note "bill millers" as a store-name workaround; Mama Margies → 5.35. Split = the offer's exact $12.95. The missing edit surface (store/tip/miles + cash tips, per-drop splits, per-leg mileage) is **#688** (grounded plan on the issue; per-leg odometer data verified already wired — every lifecycle event carries `metadata.odometer`).
5. **Hub copy says "dashes"** — platform-flavored vocabulary for the generic session concept → **#694**.
6. **Session-2 unassign captured cleanly**: accepted $24.25 H-E-B shop 18:39, arrived 18:52, unassigned (dev-confirmed), session grace-closed 19:10–19:11 with a CORRECT $0.00 reported summary. The unassign-flow frames are in the UNKNOWN captures → **#301** corpus.

### Meta / architecture

7. **The two-pickup stack capture SATISFIES the #526 field gate** (offer frame + both pickup arrivals as separate tasks + per-leg odometer). Evidence posted on #526; build unblocked.
8. Log health: 8 ERROR lines total, all the EIA fetch retry class (succeeded 07-05 morning, failed on 07-06 wifi — WorkManager self-heals); `Timer Expired` WARN spam + EIA-at-ERROR are P7 residuals → **#692**. No pipeline restarts, no recognition crashes, no recovery events.
