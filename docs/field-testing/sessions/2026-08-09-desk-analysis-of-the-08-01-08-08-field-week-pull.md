## 2026-08-09 — DESK ANALYSIS of the 08-01→08-08 field week (pull 08-09): the voice died on 08-06 and nothing said so; four new Pledge leaks; two pay-split defects; the delivery receipt stopped appearing

**Date:** 2026-08-09 (dashes 2026-08-01, 08-02, 08-06, 08-07, 08-08) · **Platform(s) tested:**
DoorDash only — **zero** Uber sessions all window (11 Uber promo notifications and nothing else, so
every Uber checklist item is NO-SIGNAL, not a null result) · **Branch under test:** `master` @
`bbc36913` (post-#984 — every redesign stage #970–#983 aboard), app 0.230.0, installed 2026-07-31
11:42 · **Field conditions:** five moneyed dashes over nine days, **one single app process for the
entire window** (never restarted, never updated), 127,024 log lines, **0 ERROR**. Pull:
`~/dashbuddy/logs/2026/08/09/`; the device was purged 07-31, so this is a clean non-overlapping
slice.

### Session inventory and the money verdict

| Dash | Window | Drops | Reported | Attributed | Δ |
|---|---|---|---|---|---|
| 08-01 | 11:17–13:59 | 3 | $58.41 | $38.04 | −$20.37 |
| 08-02 | 15:49–18:43 | 3 | $68.63 | $68.63 | **exact** |
| 08-06 | 16:22–18:45 | 3 | $49.45 | $47.20 | −$2.25 |
| 08-07 | 18:13–20:30 | 3 | $52.85 | $46.30 | −$6.55 |
| 08-08 | 12:30–13:54 | 3 | $41.76 | $39.60 | −$2.16 |

(Plus one 2-minute $0 session on 08-06.) Aggregate **$271.10 reported / $239.74 attributed**,
−$31.36 (11.6 %), and **no session over-attributed** in either direction. Every one of those five
deltas is now explained by name: 08-02 reconciles to the cent; 08-01's whole −$20.37 is the
unrecognized return-order pay screen (item 9); 08-06 is mostly a $2.00 post-delivery tip push
(item 14); 08-07's −$6.55 is *exactly half* of a two-order offer (item 6, a defect); 08-08 is
post-offer tip drift on estimate-priced rows. The window's headline is not the money, though — it is
that the app spent its last three dashes silent, and the delivery receipt sheet stopped existing.

### Headline: the spoken verdict has been dead since 08-06, and nothing anywhere said so (item 1)

**1. Offer TTS returns −1 on every call from 08-06 16:28 onward → #991 (P0).** This is the dev's
own fielded complaint, and the log carries it exactly: 15 of 15 `speak()` calls on 08-06/07/08
returned `-1`, each logging `WARN/Tts: speak returned -1 — abandoning audio focus`; 11 of 11 on
08-01/08-02 had succeeded. `engine initialized` appears **once** in the whole 127k-line log, at the
07-31 install — the process never restarted, so the `TextToSpeech` instance in play on 08-08 was
eight days old. Working hypothesis: the engine's binder died (a Google TTS / Speech Services package
update between 08-02 18:06 and 08-06 16:28 would be the ordinary way for that to happen), and the
`@Singleton` `TtsEffectHandler` has no re-init path — `isReady` is set at init and never reset, so
every subsequent utterance is dispatched into a dead binding forever. Would need confirming by
checking whether a force-stop restores the voice (hence the checklist item asking exactly that).

What makes this the entry's worst finding is not the silence itself — offers were still evaluated,
carded and posted as heads-up notifications the whole time, and the dasher's money was never at risk
— it is that this is the **same silent-death family as #909/#914 (effect engine), #916 (bubble) and
#917 (odometer)**: a `@Singleton` whose one job stops happening inside a live process, with no
alarm, no counter, and no self-heal. The quadrant that #937 filled for recognition is exactly the
quadrant TTS is missing. Nothing in the app noticed for three dashes; the dev did.

### The Pledge section: four new leaks, all filed (items 2–5)

**2. `pickup_wait_survey` is RECOGNIZED and ships a raw `customer_name` → #992 (P1).** The rule has
no `redact` block at all (`matchers/rules/doordash/pickup.json5:445`); two envelopes on 08-07 carry
the customer's name verbatim. The structural point: the #910 `ID_MARKERS` node-id scan is
**UNKNOWN-only by design** (a recognized frame keeps its rule's deliberate decisions — that's what
keeps `pickup_navigation`'s merchant address raw), so a recognized rule that simply forgets `redact`
has *no* backstop underneath it. Every one of the last few Pledge findings has been an id-less or
marker-less shape; this one is the opposite and arguably worse — a surface we recognize, whose PII
we could have masked with one declaration.

**3. `dropoff_navigation`'s `arriving_at_title` ships the raw customer street address → #993 (P1).**
One envelope, 08-02 18:00. The mask for this exact node exists on `nav_arriving`
(`nav-comms.json5`) but not on the dropoff-phase rule that wins when the arrival banner inflates
over the dropoff sheet — and the giveaway is that the **same frame** masks
`bottom_sheet_address_line_1` correctly. The raw copy and the masked copy of the same address sit
side by side in one envelope. Sibling of #886's maneuver-cluster work, which covered the nav
maneuver nodes on this rule but not the arrival title.

**4. A FOURTH timeline conjugation, `Return <customer> to <store>` → #994 (P1).** Three recognized
`timeline` envelopes on 08-01. It escapes both layers at once: the rule's own `redact` enumerates
`Pickup for` / `Deliver to` / `Pickup from`, and `CustomerTextMarkers.MARKERS` doesn't carry it
either. Same class as #962's third conjugation (`Delivery to`) — DoorDash keeps inventing sentence
forms for "this order belongs to this person", and each one is a separate enumeration miss. Whether
the answer is a fourth literal or a shape-based marker is a design question, not a desk one.

**5. The receipt-scan camera persists the customer's name on id-less nodes (`Focus on <name>`)
→ #995 (P1, DEV RULING WANTED).** Five UNKNOWN envelopes, 08-07 19:46:54–19:47:05. No view id, no
marker prefix, no rule — nothing in any scrub layer can currently reach it. The open question is
which Pledge family it belongs to: a camera pointed at a paper receipt bearing a third party's name
reads as the **document-image capture** family (the license scanner, the signature pad — *blocked*,
never parsed), but it is also plainly a customer surface, which the model says we *recognize and
hash*. The two rules give opposite answers, and the answer decides whether the fix is a sensitive
rule or a recognizing rule with a `redact`. Dev call.

### Money and lineage (items 6–11)

**6. A receipt-less same-customer two-order job loses half its pay → #996 (P1).** 08-07, offer seq
1496: $13.10, two orders, one customer. Both pickups confirmed; ONE physical drop, which folded
`offerPayShare = 6.55`. The session's shortfall is $6.55 to the cent. The mechanism is that
`OfferPayFallback.owedDropoffs` splits the offer total across both owed drops, but only one drop
ever exists to claim a share, so the other half is simply never attributed to anything. #749 fixed
**closure** for this exact shape (the job closes, the next offer gets its own job — that half worked
perfectly here); it did not touch the denominator that prices it. Note this only bites on a
receipt-less job — which, per item 8, is now every job.

**7. A job that absorbed three separately-accepted offers pools their pay into an equal split
→ #997 (P1).** 08-06: offers of $10.45, $16.55 and $20.20 folded as $15.73 / $15.73 / $15.74. The
session Σ is exact, so nothing is *lost* — but every per-drop figure is wrong by up to $5.28 (50 %
error on the smallest), and per-drop figures are precisely what the store leaderboard, the earnings
heatmap, `DayPlanner`/`WeeklyPlanner`, and #975's estimate-vs-reality card are all built on. A dash
like this teaches the planner that a $10.45 order and a $20.20 order are the same order. The
encouraging part is that the mapping looks **recoverable rather than lost**: the accept →
`PICKUP_NAV_STARTED` lineage and `jobOfferHashes` both survive in the log, so this may be an
attribution defect rather than a missing-data one — would need someone to actually walk that join to
be sure.

**8. The delivery receipt sheet stopped appearing after 08-02; 9 of 9 deliveries on 08-06/07/08 were
priced by the `OFFER_PAY` estimate → #999.** Zero receipt-shaped frames — recognized *or* unknown —
exist after 08-02, and the post-drop frame sequence changed shape with them: it used to run
`dropoff_photo` → `dropoff_completed_confirm` → `delivery_summary_collapsed/expanded`, and now runs
`dropoff_photo` → *nothing* → the next `offer_popup`. Because the frames are absent from the UNKNOWN
pile too, **this is not a recognition regression** — you cannot fail to recognize a screen that was
never rendered. Hypotheses, unresolved: DoorDash changed the post-delivery flow in an app update; or
the sheet still renders but so briefly that no frame is admitted; or something about these
particular orders (all three dashes were ordinary food deliveries) skips it. It matters because
`OFFER_PAY` is an *estimate* basis — items 6 and 7 are both estimate-path defects, and an
all-estimate world is where they bite. The frustrating part is that #937's version stamp **can't
test the app-update hypothesis here** (item 12). This needs a field observation, not more desk work;
a checklist item is added.

**9. The return-order flow is entirely unmodelled → #998.** ~27 UNKNOWN frames on 08-01: return
timeline variants (19), a pay-breakdown screen reading `Original order (Base) $20.37 / Return pay
$10.19 / Total $30.56` (1), and a store-signature flow (4 — which contains a literal **signature
pad**, i.e. Pledge-sensitive surface area). The money consequence is exact: $20.37 of a $58.41 dash
(34.9 %) is unattributed, and $38.04 attributed + $20.37 unrecognized = $58.41 reported, to the
cent — the flow is the *whole* delta. #985 (the Timeline order-detail address sheet, filed 07-31) is
this same flow's already-filed sibling and re-fielded three more times this window; its two fixture
files in this pull are corpus-EXCLUDED pending that fix.

**10. A pickup-less job loses its storeKey and its offer→job link → #1000.** 08-08, Zaxbys: the
event chain runs `PICKUP_NAV_STARTED` → (no `PICKUP_ARRIVED`, no `PICKUP_CONFIRMED`) →
`DELIVERY_NAV_STARTED`. Downstream, that job has no `pickup_records` row, an empty `storeKey`, a
null `milesToStore`, and `offer_records.linkedJobId` null — it is the only unlinked accept in the
window — so it drops out of #975's estimate-vs-reality join entirely. The proximate question is why
the arrival never fired (geofence? a fast in-and-out? a missed frame?), but the interesting bit is
downstream: one missing lifecycle edge silently removes a real delivery from three separate
analytics reads at once.

**11. A D6 join miss left one drop storeless — $15.74 attributed to no merchant (08-06 third
drop).** `WARN/StateMachine: D6 join miss … 0 of 0 … across 2 stores`, fired **once** and correctly
edge-gated (the ×23 storm shape from earlier windows did not recur — that gate is doing its job).
Same job as item 7's absorbed H-E-B add-on, which is presumably not a coincidence: a job assembled
from three offers is exactly where the customer-hash join has the least to work with. That drop's
`DELIVERY_ARRIVED` also never fired, so `arrivedAt` is null and its `realizedMiles` reads 0.0 — the
same missing-edge shape as item 10, on a different job.

### Observability, log hygiene, and one fold interaction (items 12–15)

**12. #937's version stamp is blind on a long-lived process → #1003 (documented, low severity).**
`platformApps=com.doordash.driverapp@8.91.7` is constant across all nine days — which is what a
**per-process cache** does when the process spans a nine-day window that straddles the exact app
update item 8 wants to test. The cache-forever decision was deliberate and documented ("a process
restart follows an update in practice"), and this window is the case where that premise doesn't
hold: the process never restarted, so a genuine DoorDash update would be invisible in exactly the
stamp built to make platform-app change visible. Filed as a documented limitation, not a bug.

**13. A text-less ongoing-dash notification is 13.8 % of the entire log → #1001.** 17,605 lines of
`DEBUG/Classifier: UNKNOWN notification — ` (nothing after the em dash) at roughly 1 Hz. The capture
layer handles it correctly — the content-hash dedup means it produced exactly **one** file — so this
is pure firehose volume, not a data problem. It is still worth fixing: it's shortening how far back
`app.log` retention reaches before rotation, which is the diagnostic surface every one of these
analyses runs on.

**14. The Tip Update push is unrecognized → #1002.** `"A customer added $2.00 tip on a past Target
order."` at 08-06 16:34 — which names most of that dash's $2.25 residual. A post-completion tip is
real money arriving after the record froze; recognizing the push is at least a way to *see* it,
whatever the eventual answer is on whether a frozen record should learn about it.

**15. #983's gap fold and the late-sweep dash (informational, no defect).** 08-01 measures **0 gaps**
despite a real ~8-minute wait between orders, and the mechanism is now understood: that dash's
close-out sweep appended every `DELIVERY_COMPLETED` at `DASH_STOP`, so every completion sequences
*after* every accept, and no completion has a "next accept in the same dash" to pair with. That is
precisely the `0 gaps measured` failure shape the #983 checklist item tells the dev to watch for —
so it's worth recording that on a late-sweep dash it is **expected by mechanism, not a fold bug**.
The other four dashes are coherent: 7 gaps spanning 0.6–2.7 min, 5 dash-final drops correctly
excluded as tails, zero cross-dash and zero cross-day pairings.

### Re-confirmed known issues (item 16)

**16.** #985 re-fielded ×3 (the Timeline address sheet; envelopes purged from the pull dir,
corpus-excluded). **#986 is wider than filed** — the `Apt/Suite: ` **colon** form also hex-masks, 7
envelopes; comment posted on the issue. #987 ×5 more Crimson deposit notifications (the dev ruling
is still queued). #922 ~29 frames including two fully-populated offer cards; #912 ~11; #988 ×1;
#801's digit-wheel ×3 (corroborated again). **#935 did not recur** — all three collapsed-summary
captures this window are genuine receipts, correctly classified. **#731: zero flap in nine days**
(1 connect / 0 disconnects across a nine-day single process — the longest clean observation the
issue has ever had); comment posted, and it now looks like a close candidate.

### PII sweep — the rest of the tree (item 17)

**17.** Beyond items 2–5 the pull is clean, and the sweep is worth stating positively because the
new findings are narrow. Only **three** customer-name strings exist in the entire capture tree, and
all three are items 2, 4 and 5. Five raw addresses: four are **merchant** addresses (raw by design,
#886) and the fifth is item 3. Zero PINs, zero gate codes, zero `Customer Notes:` free text, zero
banking hits. `shareable.log`: zero `[scrubbed:…]` placeholders with 28 scrub-diagnostic lines fully
legible — **#862 PASS**, second clean pass. And #910 got its receipts: **mechanism 2 (a click
envelope inheriting the screen rule's redact) fired live for the first time**, on a dropoff-nav
click that arrived masked, while a pickup-nav click on the same window correctly stayed raw — the
scope discriminator holding in the field rather than only in tests. **Mechanism 3 fired 17 times**
(`user_name` ×9, `address_line_1` ×7, `address_line_2` ×1).

### Checklist sweep

**Retired (2/2, deleted from the checklist, receipts above):**

1. **#962 — points-based ratings parse → 2/2.** Second confirmation: `overallRatingPoints` 82→83,
   `tierLabel` Gold, `qualityRate` 100.0, `acceptanceRate` 40→43 all populated across snapshots,
   with `ratings` ×4 and `performance_rate_detail` ×9 recognized. The parse re-anchor holds on real
   fielded data over a nine-day span, not just the discovery browse.
2. **#910 — the five customer-PII leak sites → 2/2.** Mechanism 2's first live fire plus mechanism
   3 ×17, with `pickup_navigation`'s merchant address staying raw (no scope leak). The new leaks
   found this window (#992, #993) are **rule-side omissions, not #910 regressions** — #910 built the
   UNKNOWN-side backstop and it works; a recognized rule with no `redact` is a different gap, which
   is now written down as item 2's structural point.
3. **#937 — recognition-health liveness, the version-stamp + false-positive halves → 2/2.** Zero
   `RecognitionHealth` WARNs across five dashes, the stamp present in every envelope. Standing
   caveat carried forward: the **true-positive** half (recognition actually going quiet mid-dash)
   has still never been exercised, and the per-process cache caveat is recorded as #1003.
4. **#862 — scrub diagnostics survive the shareable-log sink → 2/2.** 28 diagnostic lines verbatim
   with their marker ids intact, zero `[scrubbed:…]` placeholders anywhere in the export.
5. **#701 — over-attribution flag, negative path → 2/2.** Delivered ≤ reported on all five moneyed
   sessions, so no false callout was possible and none appeared. The positive-fire path stays
   unobserved by nature (it needs a genuinely over-attributed dash).
6. **#438 B5 — odometer arbitration, data half → 2/2.** Σ drop-miles ≤ odometer span on every
   session (25.9/23.42, 22.0/21.98, 18.68/9.62, 9.97/8.79, 21.58/21.57), no zeros, no mid-dash
   reset. The 08-06 pair is the loose one and it's loose in the safe direction.

**Bumped to 1/2:** #967/#968 (five Offline ratings stamps — first pull whose build carries the fix),
#315 H5 (the **data** half: 14 `pickup_records`, dwell 0.0–71.1 min all minutes-scale, real store
keys including the #773 address-tier `doordash|h-e-b|@5910`).

**Held, note appended:** #884 (5 more marker fires, still Transfer **out**), #843 (sixth mechanism
corroboration — zero grants in nine days, gate denied `confirm_decline` 21×), #810 B1 (fifth clean
zero, including a three-offer merged job and a two-store stack), #438 B3 (machine half clean over 26
offers / 0 timeouts, but flagged with #991 and #1000), #888 (a fourth family sighted; #922 still
blocks), #983 (item 15's mechanism recorded on the item).

**Broken-in-part:** the **#749** same-customer item — the shape recurred on 08-07 and the closure
half worked exactly as designed, but half the job's pay vanished (item 6 / #996). Per this file's
own rule a broken finding goes to the log immediately, so it is written up above; the checklist item
stays at 1/2 for the closure half with a pointer to #996.

**Added:** **#999** (does DoorDash still show the per-delivery earnings receipt at all — the one
question this desk pass cannot answer) and **#991** (does the spoken verdict still fire, and does a
force-stop bring it back).

**No signal:** every Uber item. Zero Uber sessions in nine days — that is an absence of testing, not
a clean result, and #882/#881, #874/#875, #857, #786, #858, #861, #825, #830 and the #762 D2 family
have now gone three consecutive windows without input.

### Session work log (this desk session)

Issues filed: **#991** (P0 — TTS dead since 08-06), **#992** / **#993** / **#994** / **#995** (four
P1 Pledge leaks; #995 carries a dev ruling), **#996** / **#997** (two P1 pay-attribution defects),
**#998** (return-order flow unmodelled), **#999** (the receipt sheet's disappearance), **#1000**
(pickup-less job loses storeKey + offer link), **#1001** (text-less notification log volume),
**#1002** (Tip Update push unrecognized), **#1003** (#937 version-stamp blindness on a long-lived
process). Comments posted on **#986** (wider than filed — the colon form) and **#731** (nine days
clean; close candidate). Checklist: 6 items retired, 2 bumped to 1/2, 6 held with a dated note, 1
marked broken-in-part, 2 new items added.

### Dev follow-up (same day, after reading the report)

### Same-day campaign addendum (desk, evening — the dev's "get all issues closed" directive)

An autonomous close-out campaign ran the rest of 08-09. Recorded here because several checklist
items changed hands; the PR/issue record carries the detail.

- **Shipped and merged (all through the adversarial loop):** PR #1009 (#991 TTS re-init + liveness
  — the voice fix), PR #1010 (redact batch A: #992–#995, #920), PR #1012 (#996/#997 per-offer pay
  attribution — built to a REVISED design after the first review proved mint-time slot lineage
  untrustworthy; the amendment lives on #997), PR #1013 (#1000 anchorless offer link,
  PROJECTOR_VERSION 10), PR #1014 (redact batch B: #986/#934/#924 + the #985 sheet), PR #1016
  (#1015 — a deterministic UTC-Monday CI failure found when it blocked #1014: the window≡enum test
  pinned a fixed zone against the enum path's systemDefault).
- **Desk-audit closures (evidence on each issue):** #806 (superseded — 303-envelope sweep clean),
  #337 (field-validated 10/10 incl. the original decoy string; item retired below), #863 (44/44
  clean resolutions post-fix), #911 (root-caused to the by-design structural-hash dedup).
  **#294 CONFIRMED as a live defect** (dropoff-completion flows resume GPS at the door — item
  rewritten below), **#827 rescoped** (the "Unknown Store" null-parse is a desk-fixable
  `hasTextContaining: "min"` self-shadow, no longer capture-gated).
- **Security-review catch worth remembering:** batch B's first cut quoted REAL fielded customer
  PII (street+apt, a verbatim gate note, entry codes) in the new rules' own `comment` fields —
  and rule comments ship in the canonicalized APK asset. Sanitized pre-merge to invented
  same-shape equivalents; the raw 07-31 source envelopes were purged after the merge. Rule
  comments are now part of the PII-sweep surface, by precedent.
- **Checklist mutations in this pass:** #337 retired 2/2; #294 rewritten to the confirmed
  mechanism at 1/2; six new items added for PRs #1011–#1014 (above).
- **Standing note:** the dasher's quick-decline automation has been consent-gate-denied since the
  07-31 install (52/52, zero grants) — working as designed, but the #843 consent-prompt UI half
  has still never been exercised by the dev. Grant the capability (or report the prompt never
  appearing) on the next app open.


1. **Item 8's mystery answered from the field:** the receipt sheet doesn't show when the dash was
   started **out-of-zone** in the **"Dash Along the Way"** status — all three receipt-less dashes
   were that shape. Dev reads it as a Dasher-app regression. #999 re-scoped to verification (both
   halves: in-zone receipt returns; out-of-zone absence reproduces — checklist item updated), and
   #996/#997 promoted in practical priority: receipt-less settlement is a routine mode now, so the
   estimate-path split defects are the numbers that stick.
2. **#995 ruled:** the receipt-scan camera is *not* document-image family — capturing the screen
   text is fine — but the customer name on it is a customer name like any other: **redact it just
   like the rest** (recognizing rule + `redact` on the two name nodes; issue retitled to the
   decided direction).
3. **New dev request → #1005:** the dropoff bubble notification rendered "Dropping off for HEB
   Customer" (DoorDash's persona/placeholder name walked straight into our copy). Requested copy:
   title **"Dropoff"**, body **"\<STORE\> Order"** — which also stops putting customer-name-shaped
   strings into system notifications at all.
4. **New dev feedback → #1006:** the redesigned analytics screens carry **too much text copy** —
   distracting. A copy-density audit + trim is filed, with the §9 honesty semantics explicitly
   preserved (condense the rendering, never delete the disclosure).
5. **New dev direction (undecided) → #1007:** pull the lifetime-scoped **Patterns** tab out of the
   windowed analytics hub — either a Home entry tile to a standalone screen or integration into
   the dashboard; shape TBD by dev or a design pass.
