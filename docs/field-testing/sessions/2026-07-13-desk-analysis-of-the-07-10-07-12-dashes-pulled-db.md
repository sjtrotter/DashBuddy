## 2026-07-13 — desk analysis of the 07-10 & 07-12 dashes (pulled db/logs/captures; playbook-first)

- **Date:** 2026-07-13 (dashes analyzed: 07-10 evening — 2 sessions, first-ever desk pass on them;
  07-12 afternoon/evening — 3 sessions)
- **Platform(s) tested:** DoorDash
- **Branch under test:** 07-10 = the 07-10 morning reinstall (pre-wave); 07-12 = `master` @
  `76966bc1` (post-#763 — carries the whole 07-11→12 wave: #736/#745/#749/#752 unassign+lineage,
  #688B per-leg mileage, #159 stores, #733 hash join, #588 shop-rate reset, #660p2, #630, #763
  observability; does NOT carry #767/#768/#769/#770, which merged after install)
- **Field conditions:** 5 dashes total in pull, $99.80 reported across the two days; heavy H-E-B
  shop volume (4 distinct physical H-E-Bs) + first Parry's Pizzeria sighting; no unassigns, no
  multi-pickup stacks, no GoPuff, no orphaned deliveries this pull. Data at
  `~/dashbuddy/logs/2026/07/13/` (db, 3 rotated app.logs, shareable.log, 1539 captures).
  Desk-validation playbook run FIRST per protocol; checklist confirmations recorded there.

### Bugs

1. **Chat INFO line leaks the raw merchant name into the exportable log (Principle 7).**
   `INFO/Chat: message posted [H-E-B's customer] (27 chars)` — 9 hits across 07-07→07-12 in
   `shareable.log` (also `[Willie's Grill & Icehouse]`, `[Parry’s Pizzeria & Taphouse's customer]`).
   The chat persona label is "<store>'s customer" (#568 vocabulary), so the merchant name rides the
   INFO milestone into the exported bug report. The sink scrubber keys on customer markers
   (`SensitiveTextMarkers`), not store names — so this is a call-site leak of exactly the class the
   #551 campaign cleaned (`Pickup: H-E-B` was the original receipt). Likely fix direction: drop or
   genericize the persona in the INFO line (the char count is the useful part); the DEBUG firehose
   can keep the persona.
   - **Status:** Open — filed #772 (2026-07-13).

### Open questions / investigations

2. **All four physical H-E-Bs conflate into ONE chain-only store entity (`doordash|h-e-b|`).**
   `pickup_records` holds four distinct street addresses (Alamo Ranch / N Loop 1604 W / W I-10 /
   Babcock Rd) but `stores` has a single H-E-B row with an EMPTY running key — the exact
   `…|h-e-b|` shape the #159 checklist item warns about. This looks like the **named
   same-chain-two-locations residual** from the #159 design vet, not a regression: the running key
   comes from payout-receipt store forms, and H-E-B receipts evidently say just "H-E-B" (Parry's,
   by contrast, keyed live with `…|stone oak`; Sonic with `…|5703`). Consequence: the dev's
   DOMINANT store folds 11 deliveries across 4 physical locations into one Patterns report card
   with blended dwell stats. The address evidence to disambiguate already exists in
   `pickup_records.storeAddress`. One possibility: an address-derived running-key fallback when the
   receipt form is chain-bare — needs a design pass against D2/F5/F7 (keys must stay
   refold-deterministic).
   - **Status:** Open — filed #773 (2026-07-13).

3. **#731 NotificationListener flap has VANISHED — environmental root-cause now strongly supported.**
   Flap lines/day: 156 (07-07) → 142 (07-08) → 192 (07-09) → 8 (07-10: two disconnect/connect
   pairs at 02:24/02:55, i.e. BEFORE the 03:19 reinstall, then clean) → **07-12: one connect at
   process start, zero disconnects across ~5.5 h of dashing** (the #763 counter line
   `connected (count=1 this process)` is working). Hypothesis strengthened: the old install's
   battery/standby state drove the flap; the fresh install reset it. No code defect in evidence.
   If the flap returns, the #763 counters will quantify it; until then #731 stays data-gated —
   suggest parking it pending a recurrence rather than building anything.

### Verification TODOs (desk-resolved — checklist updated)

4. **Money path: perfect reconciliation.** Delivered (incl. cash) == reported EXACTLY on **all 13
   lifetime sessions** — zero unattributed, zero over-attribution, no "(No session)" bucket. The
   07-12 mixed-basis session (est. OFFER_PAY $10.75 + RECEIPT_TOTAL $17.00) reconciled to the cent.
5. **#688B per-leg mileage: every invariant held.** All 6 new drops carry both legs summing to
   `realizedMiles`; Σ per-drop ≤ session span on every session (two boundary-exact cases:
   21.80=21.80, 12.75≤12.76); the v5→6 refold retroactively repartitioned history — the 07-05
   Bill Miller/Mama Margies stack now reads 3.20/3.55 mi per drop instead of the old 6.76/0.0.
6. **#733: the D6 storm is gone.** 0 join-miss WARNs on 07-12 (all 23 in the log are dated 07-08,
   pre-fix); every drop landed under a real store. (No multi-pickup job fielded, so the join's
   hard case is still unexercised.)
7. **#588 shop-rate relearn: textbook.** `[doordash]`-tagged, `n` climbing 1→4 over the day's 4
   shops, learned mean 0.49→0.67/min converging off the 0.8 seed toward the dev's real pace.
8. **#438 B4: zero wrong-offer taps.** All 5 notification-tap `(offer=<hash>)` lines exact-match
   their resolved `OFFER_DECLINED` events (3 in rapid succession 19:22–19:23 on distinct offers).
9. **#438 B3 machine half again clean:** all 5 new drops costed `OFFER_FROZEN` at cpm 0.351.
10. **Log health (Principle 7): zero ERROR lines; WARN = 3 shapes only** (16 tie WARNs — see #11 —
   plus 7 normal `GRACE_COMMIT` wakes). The channel is legible. Capture redaction spot-check clean
   (per-customer `[redacted:xxxx]` masks, no raw names in envelopes).
11. **#734 premise re-confirmed a 3rd time (pre-fix build):** 12× `confirm_decline` + 4×
   `expand_earnings` "No decisive match among 2 verified candidates … clicking first" WARNs on
   07-12. The build predates #770 (merged later that evening), so this is the last dataset that
   should ever show "clicking first" — the next pull validates the abort-to-manual flip.

### Field UX context

12. **Recognition gaps, all low-volume/known families:** 69 window UNKNOWNs on 07-12 = 23 empty
   transitional trees + the #550 shopping sub-flow (Scan Failed / Enter PLU), the help/resolution
   flow ("Tell us what happened…"), offer-popup PROMO variants (Silver-priority / "paid more as a
   Pro" banners — the offers themselves still recognized on sibling frames), an earnings-history
   screen, and one regular-dash "Navigate to zone" frame. 59 click UNKNOWNs are the same shopping
   family (Found empty shelf / Add to cart / Confirm) + nav taps. Nothing new worth a rule PR ahead
   of the #550 family work.
