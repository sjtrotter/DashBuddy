## 2026-07-30 — DESK ANALYSIS of the 07-29 evening dash (pull 07-30): first dash on the #914 build — the engine LIVED, money reconciles to the cent

**Date:** 2026-07-30 (dash 2026-07-29 ~15:40–19:27) · **Platform(s):** DoorDash only (Uber never
online; 2 Uber promo pushes all evening) · **Branch under test:** `master` @ `ff99eb8f`
(post-#914/#915, reinstalled 07-29 ~11:02) · **Field conditions:** 3 sessions (one 3.4 h main dash
+ two short early-offline stubs), 12 offers → 4 accepts (3× H-E-B shop, 1× Home Depot) / 8
declines / 0 timeouts, Evidence capture ON (master + Offer). Pull: `~/dashbuddy/logs/2026/07/30/`
(358 captures, 3 logs, shareable.log, db v15 + WAL); device purged post-pull.

### Headline: #909/#914 verdict — PASS, unambiguously

The exact arming condition that killed 07-28 (an evidence-enabled screenshot) fired 21 times and
the engine survived them all: `app_events` continuous seq 1268–1325 with zero cliff, 0 ERROR / 0
`PatternSyntaxException` anywhere, every log-observed milestone 1:1 with events, and the last
drained effect landed 07-30 07:33 — ~12 h past the first screenshot. The #914 defense layers
(Throwable isolation, restart supervisor) went **untriggered** — right outcome (nothing throws
now), but they still have zero field evidence. Checklist → 1/2.

### Money: exact reconciliation

Σ delivery `realizedPay` (27.37 + 23.45 + 13.95 + 8.70) = **$73.47 = `reportedEarnings`** — zero
unattributed, zero orphans, all four rows fully legged (`milesToStore`+`milesToDropoff` =
`realizedMiles` to 6 dp; Σ 44.66 mi ≤ odo span 44.93). Frozen economics uniform
(`fuel+nonfuel == cpm` ✓); pickup dwells sane (3.9–29.1 min). One parse wobble: seq 1289 folded
`RECEIPT_TOTAL` (no per-drop pay) because the expanded summary rendered its pay rows label-first —
harmless single-drop, would defeat a #630 stacked split → filed **#921**.

### WARN/ERROR census

9 WARN / 0 ERROR, all defended invariants (7 `GRACE_COMMIT` timers at exactly the destructive
commits, 2 first-per-process notif-listener disconnects per #731 policy). The sensitive gate
dropped 21 banking frames across three DasherDirect visits (incl. one `crimson_balance`) — zero
banking text reached disk.

### Checklist sweep (details live in the mutated items above)

Bumped to 1/2: #909/#914, #885/#886 (textbook, incl. the pickup-navigation-stays-raw
discriminator), #889 (with the F4 fused-Apt residual → fixed same-day, PR #927), #887 (proven on
the incremental path), #867/#873 (write side). Amended in place: #843 (third mechanism
corroboration; zero automation across 12 offers pre-consent), #888 (2 of 8 families sighted;
accept-spinner covered 1 of 4 accepts → #922), #862 (un-exercised; F1 is a shape it can't see),
#859 (DoorDash 12/12 supporting evidence, Uber-gated), #428-B (still un-flipped), #796 (the
"verify it masks" residual FAILED → #910 V5). No-evidence: all Uber-gated items + #884/#795/#749/
#501/#630/#736/#752/#660p2/#810-B2 (triggers absent; negatives recorded).

### Validated & retired this pass (second clean confirmation)

1. **#823 Phase 1 / PR #848 — units-denominated shop time estimate → 2/2.** Second abundant
   units day: 6 platform-tagged `ShopRate` learns (items/min n=35→38 @ 0.79–0.80, items:units
   n=7→8 @ 0.84–0.86), units offers throughout, and estimate sanity on all 4 accepted jobs (est
   vs actual +2% to +23%, no 2×-class inflation). #588's sibling learner got a clean second data
   point the same evening.
2. **Heads-up banner acts on the RIGHT offer (#438 B4) → 2/2 (desk half; retired).** Third
   consecutive clean pull: 3/3 `OfferActionReceiver` decline taps exact-match their resolved
   events by full hash (8/8 across three pulls total, zero mis-aims, incl. rapid-succession
   shapes). The dev-eyes banner-UX half never surfaced a complaint across five dashes; re-add a
   targeted item if banner UX ever misbehaves.

### PII sweep findings (the Pledge section)

- **F1 (HIGH): #910 field-confirmed live.** The UNKNOWN pre-render workflow sheet shipped a raw
  customer name as `user_name_label`='Delivery for' + bare `user_name` sibling (18:14:06, 34 ms
  before its correctly-masked recognized twin). Split-node: the marker backstop structurally
  cannot reach it (`"Delivery for "` has been in the SSOT since #624 — #910's V5 premise was
  corrected on the issue). Recurs once per job. The #910 build (in flight this session) closes it
  with the node-ID backstop.
- **F1b (MED): chat compose box** (`message_input` EditText) serialized verbatim in an UNKNOWN
  click envelope — dasher-authored free text naming customer order contents → **#919**.
- **F2 (MED): `shopping_item` "Customer Notes"** value raw on a recognized surface (benign this
  dash; the field can carry gate codes) → **#920**.
- **F4 (LOW-MED): fused `Apt <n>` kept its 4-hex** on two envelopes while the split form
  correctly plained → fixed same-day: **PR #927** (plainMask on all 8 uber+doordash fused-Apt
  entries, merged).
- **F3 (pending dev ruling): `earnings_deposit`** notification retains "…deposited to your
  DoorDash Crimson account" + amount on the recognized path — second field instance; the ruling
  on this class is still queued for the dev.
- **F6 (LOW, hardening): DasherDirect entry skeleton** (`dxdr_nav_host_fragment` + loading)
  reaches disk before banking text renders — no leak occurred; id-anchored sensitive arm proposed
  → **#924**.
- shareable.log: fully PII-safe (296 lines, zero merchant/customer/address tokens, zero
  `[scrubbed:…]`); good tag hygiene across 11 tags.

### UNKNOWN census (86 files) → recognition work

Offer-card inflation family 6/evening incl. two fully-populated Decline-less cards (one a rare
Chili's+Pizza Hut two-store stack) → **#922**; dash-control sheet with live "This dash · $50.82"
(== deliveries 1+2 exactly; also missed by `timeline`'s both arms — no scheduled end time) →
**#923**; ratings/rewards drill-down variants ×8 → closed by **PR #926** (corpus pass: 5
fixtures, 3 recognize-only broadenings, additive-only golden); nav skeletons / drawer skeletons /
#801-class transients — left alone.

### Dev field observations relayed this session (recorded, then filed)

1. **Stationary odometer drift:** 2+ phantom miles accrued before leaving the house on 07-28.
   Desk trace: `processLocation` accrues any inter-fix displacement > 5 m with no accuracy gate
   (and `Coordinates` drops `Location.accuracy` entirely) — indoor GPS jitter compounds at 2–5 s
   cadence; not crash-related. → **#918** (with candidate gating directions; interacts with
   #917's heartbeat observability).
2. **"Could we auto-recover / stop losing mileage in the 07-28 engine-death state?"** → **#917**:
   odometer heartbeat log line (engine-independent, reconstructable from logs), dasher-visible
   engine-death alarm (#913's user-facing half), state-observed odometer arbitration (the
   StartOdometer-is-an-effect gap is why post-death dashes measured nothing).

### Session work log (this desk session)

Issues filed: #917–#925 (two dev observations, five desk findings, two recognition
opportunities), #929 (corpus-pruner eviction sort footgun, from the #926 pass), #934
(`address_subpremise_line` plainMask — the #895 class's id-anchored sibling, flagged by the #931
build), #935 (`delivery_summary_collapsed` misclassifies live drop-off sheets — the leak half
closed by #931, the flow-claim half filed). PRs, all MERGED: **#926** (corpus, +5 fixtures/3
ratings broadenings; adversarial review found + fixed one committed `Apt [redacted:<hex>]` token —
the F4 class — before merge), **#927** (#895 fused-Apt plainMask ×8), **#928** (#907 release lint:
es-rUS→es fold per the locale allowlist, `LocaleAllowlistGuardTest`, lintVitalRelease gated in PR
CI — which surfaced its own footgun: a PR body *describing* the skip token skips its own CI),
**#931** (#910: the three-mechanism privacy build — rule redacts, click envelopes inherit the
screen rule's redact via `ScreenContext`, `CustomerTextMarkers.ID_MARKERS` UNKNOWN backstop;
line-by-line adversarial review + a dedicated fable `/security-review` both CLEAN — "monotonically
privacy-tightening on every traced path"), **#932** (#900 matchFirst wire-scoping), **#930** (#902
workflow_dispatch escape hatch + `[force ci]` override — which hit the very dispatch-refusal class
it mitigates; the issue's tree-nudge recipe cleared it), and **#933** — the dev-commissioned
**ground-up adversarial review** (docs-only; `docs/adversarial-review/2026-07-30-ground-up-review.md`,
verdict "viable with binding constraints"). On the dev's go its 12 recommended issues were
validated against source and filed as **#936–#947** (evaluator null-distance fail-open →
no-verdict; recognition-health liveness; en-locale boundary of the marker layers; instrumented-CI
nightly; propExplore nightly; negative corpus + intake hardening ⊃ #929; UI-edge SSOT batch;
FlowCardItem split; UDF cleanups; checklist triage policy [dev-gated]; ROADMAP retire-or-refresh
[dev-gated]; test hardening batch) — builds launched same-session for #936/#947 (+ PR #948 for
#940). #835 (`stateDescription` scrub SSOT) build launched on the post-#931 tree.
`CLAUDE.local.md` updated: GitHub network fixed (dev), retry loops retired.
