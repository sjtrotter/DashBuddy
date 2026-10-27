## 2026-07-26 — five-session day, heavy shopping (pull 2026-07-27, desk-analyzed 2026-07-27)

**Platform(s) tested:** DoorDash (4 sessions, $151.25 total) + one 3-minute Uber window.
**Branch under test:** `ddd9e7ff` (installed 07-24 17:58) — the 07-26 build wave (#868–#879) is NOT
on this device; wave items scored as BASELINE only. Device purged + cleared for reinstall post-pull.
**Field conditions:** San Antonio; units-heavy shop day (7 units-denominated offers); the Uber
window was crippled by Uber's own overlay permission being OFF ("Unable to go online / Turn on
overlay permissions" — 3 offers in 3 min, weight conclusions accordingly).
**Headline: cleanest pull on record.** 0 ERROR, 19 WARN (15 = normal grace timers, 4 = the privacy
layer working) across 11.2 MB; money EXACT on all four sessions (80.55 / 48.40 / 22.30 + a $0);
zero orphans, zero mismatch tripwires, zero D6, zero #863 recurrences (14/14 confirm_declines
clean), zero pipeline restarts; projector fully drained at v8.

### Validated / advanced this entry
- **#827-P1 + #813 — VALIDATED 2/2, retired.** Second pass: both Uber card receipts exact
  (`14 min (3.1 mi)` → 3.1; `39 min (14.0 mi)` → 14.0); 0/2 raw intersection lines (both
  `[redacted:*]`); zero `{` filenames in 46 screenshot saves.
- **#733 — VALIDATED 2/2, retired (real positive).** The two-store single-customer job finally
  fielded (Dunkin' + Taco Palenque, 2 pickups → 1 drop): landed under a REAL store
  (`doordash|dunkin'|357430`) via the earliest-confirmed lineage arm, zero D6 WARNs pull-wide.
- **#630 — VALIDATED 2/2, retired.** Pizza Hut + CVS stack: 6.55 + 6.55 = 13.10 exactly, both
  DROP_SHARE, no $0/NONE row, zero mid-stack tripwires.
- **#823-P1 → 1/2** (first real trigger: 7 units offers, learner 0→n=5 @ 0.83, estimates sane).
  **#749 → 1/2** (the job-61 shape did not recur; closed via the strict arm — coverage-arm path
  itself still unexercised). **#810-B1** third clean false-positive. **#843** ungranted-accept
  corroboration (3 automations fired all day; accept never auto-fired over 10 manual accepts).
  **#588** learner n=26→33 @ 0.79. **#688B** invariant hairline-tight on all sessions (43.47 ≤
  43.48). **#691** OFFER_PAY fallback minted real dollars on 2 receipt-less shop drops.

### Bugs / new findings (issues filed 2026-07-27)
1. **Raw customer name on a RECOGNIZED surface** — `dropoff_multi_order_confirm` redact
   enumeration gap (+ the double-space `Firstname␣␣I.` shape evades the single-space desk recipe)
   → **#885** (HIGH, Pledge). Only raw name in the pull.
2. **Raw customer address in nav maneuver text** — `dropoff_navigation` masks the sheet but not
   the embedded `primaryManeuverText` full-address restatement → **#886** (HIGH, Pledge).
3. **Dasher-banking click leaks** — `Transfer $<amt>` ×2 + `Transfer in` evade the "Transfer out"
   marker (window side held; click envelope is the channel) → on **#884**; fielded files purged
   per the standing procedure.
4. **`stores` orphan rows** — address-tier key left behind when the receipt-tier key supersedes
   (2 phantom zero-visit stores from one stack) → **#887**.
5. **Recognition batch** — accept-spinner (every accept drops its follow-frame), the
   economics-bearing mid-shop pay-adjustment sheet, geofence help flow, handed-directly confirm,
   pizza-bag, substitution sheet, picked-up confirm, wait-survey variant → **#888**.
6. **Single-glyph hash-mask brute-forceable** (`[redacted:5fec]` on a 1-char node) → **#889**.
7. **Uber Match anatomy nailed (feeds #251/#881/#882):** both captured cards CTA=`Match`
   (byte-identical bounds; no Accept counterexample in-window); `Matching may take longer` is
   per-card match chrome; the `Delivery (2)` stack's `orders[]` holds ONE order (chip-anchored
   `presentationKey` — #882's defect currently SHIELDS #830 from store-cycling churn; fix must
   pair display + identity). A live X-tap decline during offer B recorded as TIMEOUT with its
   click envelope on disk — the cleanest #786 before-picture fixture yet.
8. **#867 before-picture:** "Done Ubering!" filed into the DoorDash session's chat during the
   dual-online overlap — exactly the `EndSession` shape #873's review caught; offer lines all
   filed correctly. **#857 baseline:** 1 forged offline in 176 s Uber-online (healed by grace,
   375 ms); even the deliberate stop rode the offline frame, not the go_offline click.
9. **Awaiting dev ruling (unclassified, reported):** the recognized `earnings_deposit`
   notification stores the dasher's own deposit amounts; `shopping_item` stores `Customer Notes:`
   free text raw (no PII in these instances; historically the gate-code carrier class).

### Meta
- One `DELIVERY_COMPLETED` without `DELIVERY_ARRIVED` (the #700 suppressed-arrival residual class)
  → that drop's per-leg trail blank, legacy-delta fallback correct per spec.
- Pull sanitized in place same-day (standing decision): 07-25 dir (98 files, 155 occurrences,
  stable fakes, zero residuals; 1 banking file purged) + 07-27 dir (run in progress at entry
  time). shareable.log: 0 PII across all recipes; hash stability held cross-surface.
- Dev decisions recorded on-issue this session: #251 (offerKind match|direct → #881; batch-eval
  helper; best-match announcement, ordinal-disambiguated, NO new permissions), #867 (follow-active
  + manual switcher; debug presentation toggle incl. merged-stack test mode; platform labels in
  every mode), evidence PNGs stay as-is (pixel-redact answer shaped as #883).
