## 2026-09-05 (desk analysis of the 09-06 pull — first field run of #1029/#1030/#1032/#1036/#1052/#1057/#1066)

**Date:** 2026-09-05 · **Platform(s) tested:** DoorDash (app **8.95.6** — moved from 8.93.7; every one of the 493
envelopes) · **Branch under test:** `master` at `38036999` (read straight off the logs — `DashBuddy 0.230.0+38036999
starting`, #1066's first pull), installed 14:35; the 07:45–09:19 `early_offline` session predates the install and
is old-build data · **Field conditions:** three afternoon/evening dashes (16:19–17:46 $34.60 · 17:46–18:46 $22.25 ·
18:54–20:37 $48.38), all ended on the summary screen; 6 deliveries; Uber Offline the whole slice; no pause/resume
mid-spin, no unassign, no orphan. 493 captures (48 UNKNOWN screens, 63 UNKNOWN clicks, 5 UNKNOWN notifs), zero
`ERROR` lines, `restarts=0`, 16 % UNKNOWN ratio, no `RecognitionHealth` alarm, `shareable.log` PII-free.
Issues filed this analysis: **#1078**, **#1079**. Not on this build: #1058/#1059/#1063/#1064/#1033.

### Bugs

**1. Session 409 silently lost a delivered drop — $9.95 unattributed → #1078.** Job −413 had two dropoffs; `DASH_STOP`
(seq 1881) sequenced AHEAD of the second drop's own `DELIVERY_CONFIRMED` (1882) on the same 17:46:24 step, and only
one `DELIVERY_COMPLETED` (1883) emitted although the shadow projector logged both customer hashes. The offer quoted
$19.90, the surviving drop was stamped $9.95 (`PER_OFFER_STORE`), and $34.60 − $24.65 = $9.95 closes the session's
gap to the cent. *Hypothesis:* End Dash tapped on the second doorstep; the SESSION_END grace committed with the
close-out sweep completing only the already-retired task and `endSession`'s bail force-stamping the other. Nothing
WARNed and `session_records.deliveries` still reads 2. Would need the 17:44–17:47 sequence replayed through
`SessionReplay.reduceMixed` to confirm.
- **Status:** Open (#1078).

**2. Every 8.95.6 receipt auto-expands just past the 2.5 s grace — 3 for 3.** Collapsed→`GRACE_COMMIT`→expanded:
+172 ms (job −418), +463 ms (−424), +41 ms (−427); the collapsed→expanded interval is a tight 2.56–3.00 s, i.e. an
animation, not a tap. Consequence right through the read model: `parsedPay` null on all three → `RECEIPT_TOTAL`, no
tip/base, empty `payoutStoreForms` (#653's guard silently off); on the stacked job −418 one drop took the whole
$22.25 and its sibling folded `NONE` with NULL pay (no receipt AND `offerPayShare` null — the #691 FIX-1 residual).
This is #1033's premise, confirmed: the old grace is not occasionally late on 8.95.6, it is always late. #1033
(8 s + re-price) is on the 09-06 build.
- **Status:** Shipped in #1072 (2026-09-06) — first field test pending; the `NONE` sibling stays the #691 FIX-1 residual.

**3. Two 8.95.6 sheet families still unruled + one pickup-side redact asymmetry → #1079.** `alcoholWarningBottomSheet`
(not #1058's leak A — a separate warning sheet, no PII in this capture); the id-less `address_instructions_view`
"Delivery for <customer>" sheet ×4, two of which the #910 `user_name` backstop masked (the backstop acting as the
primary control, which #1058 argued it must not); and `address_subpremise_line` rendering the MERCHANT's suite raw on
6 recognized `pickup_*` frames while the same id is in `ID_MARKERS` — `pickup.json5` has no `Apt` entry, so #986's
scan cannot see it. Needs a ruling (mask, or document the pickup exemption beside #886).
- **Status:** Open (#1079).

### Verification (desk)

**4. Working:** #1030 (no fake $0 — 49/50 early_offline rows NULL), #1032 (summary recognized 3/3), #1036 (census
present; `waiting_for_offer` ×5 is the benign pre-render class), #1057/#918 (2,310 fixes, 0 rejected, 190 ignored,
plausible spans), #1066 (build read from the logs), #159/#773/#1000 store + offer linkage, #688B (Σ realizedMiles ≤
span everywhere), #588 shop rate learning (`0.76/min n=70`), #731 (no listener flaps), #924 (DasherDirect: 217
`sensitive.dasher_direct` drops, zero `dxdr_*` UNKNOWN — **2/2, item retired below**), the #992–#995/#920 redact
batch (zero raw `For …`/`Return …`/`Focus on …`/`Customer Notes` hits across `captures/`).
**Half:** #1029 (totals + settle commits correct on 8.95.6; `DROP_SHARE`/tips unproven because of item 2), #1034
(no negative window occurred), #996/#997 (two jobs match their quote to the cent; job −413 is item 1).
**Not exercised:** #1052 (no pause/resume mid-spin, no platform switch), #1059 (no Persona/Red Card/passport surface;
the existing licence anchors dropped 17 `id_verification` frames), #736/#752, #660p2, #810 B2, #991 (24 utterances,
0 failures — ladder untouched).
**Recurring, not on device:** #1063 (4 more offer cards with a real store row, no functional loss), #1058 leak B ×2.

### Open questions

**5. #843 consent never answered → #577 quick-decline is OFF.** `Consent: reconciled 4 capabilit(ies) … none granted —
awaiting consent` on all three process starts and 34 × `Denied confirm_decline (fail closed)`. Correct behaviour; the
log cannot tell "never saw the prompt" from "Not now". Dev-eyes: Settings → Data & Privacy → Automation & Consent.

**6. DoorDash 8.95.6.** The #1029 re-anchors held on the bump, but the frozen corpus is 8.93.7-era and the new
id-less sheets (item 3) are the first 8.95.6-only shapes. Worth a corpus intake from this slice.

### Retired from the checklist this analysis

- **#924 (PR #1014) — DasherDirect is blocked from the first frame.** Confirmed 2/2 (desk 09-05 + desk 09-06).
