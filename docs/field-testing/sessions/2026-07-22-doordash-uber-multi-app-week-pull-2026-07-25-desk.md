## 2026-07-22→24 — DoorDash + Uber multi-app week (pull 2026-07-25, desk-analyzed 2026-07-26)

**Platform(s) tested:** DoorDash + Uber, heavily multi-apped (both online simultaneously, frequent
app-switching, some idle/gaming stretches).
**Branch under test:** TWO builds — Build A (through 07-24 17:58:30; carried #830/#827/#825/#795/#796,
proven by `presentationKey` present on 07-23 events) and Build B = `master` @ `ddd9e7ff` (installed
07-24 17:58:30 exactly; adds the #843/#845/#847/#848 wave + #416/#590 + the Phase-6 feature modules).
`versionName` stayed 0.230.0 across both — partition by the install wall-clock, not version or date.
**Field conditions:** San Antonio, evening dashes 07-23 and 07-24; 1273 captures, 4 log rotations +
`app.log`, DB at v15/projector v8. Device purged post-pull (standing step 3b).
**Money:** exact to the cent on all three money-bearing sessions ($47.08 / $26.78 / $12.90 — Σ
attributed == reported, zero orphans, zero unattributed). Zero ERROR lines in ~87k; zero pipeline
restarts; `notifListener` flap still gone.

### Bugs / root causes (all desk-confirmed from this pull; issues filed 2026-07-26)

#### 1. Uber declines recorded as timeouts — recording defect, not display (#786/#826/#251)
Dasher observation was "we record the decline, the bubble displays timeout" — desk shows the
**inverse**: the ledger has 162/162 Uber offers `OFFER_TIMEOUT` (lifetime, zero `OFFER_DECLINED`),
resolving in 0.4–18 s vs genuine DoorDash expiries at 45–120 s. `OFFER_TIMEOUT` is the
`resolveOfferOutcome` fall-through default ("no click evidence"), not an expiry finding. What read
as "declined" inside the bubble is the **evaluation verdict banner** ("DECLINE", red/X — advice),
which visually dominates the small "Timed out" `OutcomeChip` on the same resolved card — display
ambiguity filed as #864. Dev identified the decline control: the anonymous **106×106 textless
Button** at the card's right edge (5 on disk; board variant 80×80). Click-side mechanics: UNKNOWN
clicks dedup on a text-less/bounds-less structural hash, so repeat decline taps collapse to one
LRU entry (62 pre-gate anonymous clicks in Uber-only windows vs 24 on disk) — but a decline click
RULE classifies before the gate and bypasses all of it, so #786 alone recovers every witnessed
decline (~36–40% of resolutions show one; the rest have no witness of any kind — swipe-dismiss is
API-invisible, the board rotates focus, requests get taken). Proven-negative: the card exposes NO
countdown (deadline inference unavailable); the platform's own "not a decline" witness exists
(`This request is no longer available` overlay → #858).

#### 2. Trip Radar: the board is the surface, and it reframes "Uber offers" (#251)
**72 of 114** Uber UNKNOWN window captures (and 14/24 UNKNOWN clicks) are the Trip Radar board —
a scrollable multi-request list over the map, each card carrying full offer anatomy + `Match` +
a textless dismiss-X. `uber.screen.offer` anchors on the single focused card
(`primary_touch_area`); board scroll/rotation re-anchors it → replace-or-leave churn. 144
"offers" in ~2 h of Uber-online is a browse feed, not 144 decisions. Full design analysis posted
to #251, deliberately framed as the generalizable **offer-board pattern** (dev direction
2026-07-26: Walmart Spark / Instacart / Shipt are board-shaped too — the focused-card model is
the special case). Key open design questions on #251: card identity/lifecycle (visibility ≠
liveness), browse-vs-presented semantics, resolution honesty for unwitnessed disappearance,
per-card actions, and privacy riding recognition.

#### 3. Uber online detection: idle_map claims offline from absence (#857)
7 real go_online/go_offline toggle pairs recorded as **13 sessions** (6 spurious splits — one
post-Build-B — plus 14 grace-absorbed false claims). 20 of 27 Online→Offline edges fired on
frames with NO offline evidence (partial renders satisfy the rule's two `notExists` vacuously;
worst specimen 115 nodes, zero text). Multi-apping is the amplifier, not the cause: the bad frame
fires while Uber is foreground; switching to DoorDash starves the 10 s grace of contradicting
frames (4 of 6 fatal splits: 8–22 DD frames, 0 Uber frames in-window). DoorDash contrast: 0/6
spurious (its idle_map requires positive markers + rejects). Correcting evidence was discarded —
Trip Radar frames (online-only proof) landed UNKNOWN inside a fatal grace window.

#### 4. Uber screenshots "fading": exit transition, not entrance (#858/#859)
Entrance grabs are clean (settle timing identical to DoorDash, ~0.9 s median). The faded ones are
the **dying card** — Uber renders the expiry message OVER the dimmed card, the rule keeps
matching (no `validate`), and the storeName parse latched the overlay: `offer_records` got
`merchantName = "This request is no longer available"`, TTS spoke it, a screenshot is named it,
and the poisoned `presentationKey` fired a spurious #830 replace (#858). Evidence-quality facets
(43% stale-layer captures, 140 screenshots for 112 offers via parsedHash re-fire, 331
`Offer - {storeName}.png` placeholder files) → #859.

#### 5. Privacy sweep (Pledge surface)
`shareable.log` CLEAN (0 merchant/customer strings in 1588 lines; #772 holding). Recognized Uber
offer surface CLEAN (0/102 raw dropoff lines — #813 redact working). New findings, all filed:
Trip Radar UNKNOWN captures carry raw customer cross-streets (34/114 window + 8/24 click) → #856
(blocked-by #251, the primary control); two bare-customer-NAME UNKNOWN variants (uncatalogued
"Switch to pick up at <store>" sheet; a `pickup_pre_arrival` partial-render race with label+name
as separate sibling nodes — a per-node prefix scan cannot join them) → posted to #806; recognized
`dropoff_pre_arrival` redact misses the `Building Name` value → #860; Uber selfie ID-verification
camera lands UNKNOWN instead of sensitive-blocked (document-image class; nil actual exposure —
camera preview contributes no a11y pixels) → #861; the sink scrub self-scrubs its own
sensitive-marker WARN in `shareable.log`, losing the diagnostic → #862. Known #806 direction-1
residual (address + gate code ×2 + apt unit on UNKNOWN DoorDash sheets) leaked as documented.
**Standing dev decisions:** the 2302 on-device evidence PNGs are unredacted images (customer
cross-streets visible in pulled samples) — retention policy is the dev's call; ditto purging the
07-25 pull's PII-bearing fixtures once #856/#858/#251 builds no longer need them.

#### 6. Smaller finds
Intermittent `confirm_decline` target-resolution failure (4×, one post-Build-B, fail-closed to
manual, #788-adjacent shape) → #863. Three performance-hub recognition gaps survive #837 → #865.
`idle_map`'s false frames also sat inside 2 of 162 live offer windows (minor churn contributor).
07-23 evening UNKNOWN census is a lower bound (burst cap hit at 20:55).

### Validated / retired this entry
- **#794 DasherDirect Transfer-out block — VALIDATED 2/2, retired.** Second clean pass: zero
  banking/balance text in all 1273 captures; the one post-install Transfer-out UNKNOWN click was
  scrubbed at the marker layer; ~262 DasherDirect frames dropped by the sensitive gate.
- **#806 UNKNOWN customer scrub — fired-half VALIDATED 2/2, retired from checklist.** 10 scrubs,
  prefix-only WARNs, `unknownCustomerScrubs=10`. The item retires; the *residual* work continues
  on #806 itself (new variants posted there, Uber-scale variant in #856).
- **#843 consent 1/2** (mechanism proven from logs: migration cleared → nothing auto-granted →
  3 explicit grants; UI half needs dev eyes). **#830 1/2** (core invariant PASS; accept chain
  untested). **#827-P1/#813 1/2** (mi/mi fix + redact PASS). **#796 1/2** (8/11 families clean;
  gaps → #865). **#810-B1** false-positive half re-confirmed. #845/#823-P1/#825/#795/#810-B2
  INCONCLUSIVE — their trigger conditions never occurred.

### Meta
- Two desk-hint greps in checklist items (#845 `Tts` language-apply, #801 `sessionEarnings=`) have
  NO corresponding log sites in current code — a no-hit is not evidence. The hint or the log site
  needs reconciling whenever those items are next touched (SSOT lesson: a desk hint is a second
  copy of a log contract).
- Crash recovery survived an APK replacement mid-session (session-179 straddled the install:
  replayed 2 observations over the snapshot, suppressed effects during recovery, kept odometer).
  Treat that session as an artifact for offer/delivery counts.
- Uber `high_priority` offer pushes (`Uber Request` / `N new requests just came in!`) are unruled
  and arrive regardless of foreground app — relevant to #785 as a board-arrival witness.

### Close-out addendum (2026-07-26 evening — the build wave shipped)
Same-day autonomous build wave off this entry's findings, every PR through the full fable
adversarial loop (each review caught or verified something real — the #869 HIGH alone would have
shipped false declines on the fielded permission-nag sheet): **PR #868** (#861 selfie
sensitive-block), **#869** (#786 decline X rule + own-text-empty guard), **#870** (#862 scrub
self-scrub via MarkerLogId), **#871** (#860 Building Name redact + the new
`hasPrecedingSiblingText` engine predicate, extended to the completion sheet in review), **#872**
(#857 idle_map positive-offline — follow-ups #874/#875 filed from review), **#873** (#867
write-side chat session threading incl. the review-caught `EndSession` gap), **#876** (#858 expiry
overlay guard), **#877** (#865 perf-hub rules), **#879** (#859 presentationHash dedupe + throttle
engine fix + filename sanitizer). Issues closed: #857 #858 #859 #860 #861 #862 #865 #786; new from
reviews: #874 #875 #878 (unseeded fuzz lost a counterexample). Checklist items for the wave are
above (all 0/2). **Still open / dev-gated:** #251 board design sign-off (D2 promotion-only vs
sighting-mints-record), #867 display-side, #856 (blocked-by #251), #826 (accept chain — needs the
accepted-trip dash), #864, #863, evidence-PNG retention + pull-fixture purge.
