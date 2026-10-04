# DashBuddy Roadmap (living doc)

**Updated:** 2026-10-04 (post #1197 health ledger, #1199 cluster IA, #1200 trusted-envelope uploader
+ wireframe). Buckets reflect the board reality — when an issue's state changes, move it here or
this doc rots (#946: the previous revision sat untouched from 07-12 to 10-04; the agreed cadence is
**every PR that changes a bucket updates this file**, same rule as `CLAUDE.md`). Companions:
`CLAUDE.md` (architecture summary), `docs/architecture/0N-*.md` (receipt-level references),
`docs/field-testing/README.md` (the live checklist).

## Where the project is (one paragraph)

The on-device product is feature-complete for a single dasher: recognition → state machine →
analytics hub (Today · Money/Offers/Time · Playbook) with frozen-per-record economics, consented
per-action automation, CSV export, and a PII-safe shareable log. Since September the work has
shifted to the **UNKNOWN-screen census** (epic #1138, ADR-0011): hash-only skeletons from opted-in
debug phones → a self-hosted AGPL server (`sjtrotter/dashbuddy-census`, v0.10.0) that clusters them,
counts installs per label hash, posts daily recognition health, and gives the operator a dashboard to
triage unfamiliar screens — the upstream half of rule distribution (#192). DoorDash is the fielded
platform; Uber recognition exists but is thin (#826 accept undetectable, #251 multi-offer).

## Now / in flight (2026-10-04)

- **#1200 (app PR #1201 + server PR #43)** — trusted-install envelope uploader + the cluster-page
  wireframe; fixes #1196. Merging today; field check 0/2 on the checklist.
- **Census S7c remainder** (`~/dashbuddy/design/2026-10-03-s7c-phone-health-envelopes/PLAN.md`):
  A1 health ledger SHIPPED (#1197); B envelopes SHIPPED (#1200); next is **#1188** M6 — classify a
  cluster in the `Flow` vocabulary, mark parse fields, draft a rule from the wireframe.
- **Phone field validation** of #1185 (status line / Copy install id), #1197 (daily health post),
  #1200 (captures shared) — see the checklist.

## Next up (unblocked, ranked by value × boundedness)

1. **#1188** census M6 review + rule-drafting tool (needs #1200 live). Then **#1189** census coverage
   for UNKNOWN clicks/notifications; **#1175** class/id unblinding at k (design).
2. **Census server hygiene:** #1177 purge/retention vs the advertised policy + vocabulary quarantine;
   #1193 enrol/admission bucket scope (dev decides figures); #1192 withdrawal replay after a restore
   (dev decides option 1/2); #1180 pledge wording (dev decides the IP posture); #1202 `captures/`
   backup exclusion (small, app).
3. **Pledge-class recognition gaps on 8.99.x** (customer PII reaching UNKNOWN capture — each is a small
   sensitive/redact rule): #1127, #1128, #1139, #1116, #1088, #1079, #1022 (signature pad), #1126,
   #985 (in progress), #856 (Uber Trip Radar), #919 (chat compose box), #1131 (classifier DEBUG log).
   The census wireframe is now the drafting surface for these.
4. **Offer/accept correctness:** #1119 (link offer→job by evidence — replaces click-less accept
   inference), #1121 (add-on offer card unrecognized), #1104 (confirm-decline tap beats its sheet),
   #1069 (presentationKey collision), #1097 (double CONFIRM_DECLINE), #1101 (Σ > Σ quoted).
5. **Money-fold correctness:** #1120 (single-drop receipt re-priced a 2-offer job), #1108 (mixed
   mileage bases on a stacked job), #1133, #1134, #1051, #1041, #1035/#1056 (settle gate), #921.
6. **Recognition coverage batches (no PII):** #1114 residuals, #1096, #1089, #1090, #1047, #988,
   #989, #922, #923, #912, #998 (return-order flow), #1002, #1141, #999 (receipt sheet vanished?).
7. **Resilience:** #913 (wedged drain worker), #916 (bubble lifecycle unlogged), #917 (odometer
   survives an engine outage), #1164 (LinkageError backstop), #731 (notification listener flapping),
   #1076/#1083/#1084/#1086 (accepted residuals from the #1054/#1073/#1053 families).
8. **Hygiene:** #1045, #1049 (parse `optional` declaration), #1048, #1001, #1003, #906, #925, #897,
   #864, #965, #1021.

## Needs design / dev decision first

- **Evaluator redesign (#1113)** — the two-line experiment's catalogue of 16 models and the
  reconciled auto-accept path; months of qualification before any auto-accept ships.
- **#1135** Earned-as-hero historical record (reported-vs-recorded ledger). **#964** ratings trend.
- **#1137** market-pulse free-until-k≥10 cohort engine; **#1194** canonical REGION cell per install
  (ties #252 zones + ADR-0007's zone hash); both blocked by **#193**.
- **#245** ADR-0007 canonical domain schema → unblocks **#251** Uber multi-offer → **#826/#249/#250**
  Uber lifecycle. **#785** Uber per-leg notification flows (corpus-gated).
- **#527** job lifetime; **#528** per-dropoff GPS $/mi; **#756** settlement re-price at job close;
  **#816** timeline owed-set reconciliation (capture-first); **#1081** stale post:task fabrication.
- **#579** voice accept/decline (vetted: arm-once-per-dash mic FGS; hands-free is a hard requirement).
- **#254** auto-tuned time constants; **#823** shopping-list intelligence; **#550** pay-adjust toast.
- **#294/#918** odometer arbitration follow-ups (the #1057 gate shipped; stationary flows residual).
- **#883** pixel-redacted evidence screenshots; **#695** OTA app-config channel; **#723** profile screen;
  **#741** zone leaderboard; **#428** i18n (locale allowlist en/es/fr, `values-es` ~84/393 keys).
- **#945** field-testing checklist triage/aging policy (dev). **#505** replay-harness frontier.

## Blocked / parked (with the gate)

- **#246 LEGAL counsel** ← pacing gate for **#170** (disclosure flow + PRIVACY.md) and the matchers
  repo split **#637 → #636 → #638 → #640/#641** (PARKED by the 2026-07-03 keep-in-tree decision;
  reopened as an architecture question 2026-10-04 — whether the ruleset ships from its own Apache-2.0
  repo or is published by the census server; see #192).
- **#192 epic** — in-tree M1 done (#635/#639 JSON5 source + canonicalizer; #416 signature
  verification in-tree); the census (#1138/#1157) is its upstream half; distribution M2 (#640) is the
  open design question above. **#214** DSL onboarding rides with it.
- **#193/#194** academic-pillar RFCs — future; **#1137/#1194** depend on them.
- **#84** Play internal track — after the first tagged release (none pushed yet).
- **#590** phase-2 unseeded fuzzing — runs nightly behind `-Ddashbuddy.propExplore=true`; the
  remaining items are gated on #192.

## Recently resolved (2026-09 → 2026-10-04)

- **Census:** #1145/#1146 skeleton builder + publisher; #1173 `census-contract` included build
  (Apache-2.0); #1157 server v0.6.0 live (S1–S7a) → v0.7.0 VPN-only `/ops` + TOTP login (#1181) →
  v0.8.0 dashboard (#1176) → v0.9.0 platform→version cluster summary + review pages (#1199) →
  v0.10.0 envelope pairing + wireframe (#1200, fixes #1196); #1178 per-IP edge rate limits; #1179
  memory limits + first restore drill; #1191 blind memory alarm fixed; #1182 phone uploader (S7b);
  #1185 reset identity + Copy install id; #1197 daily recognition-health ledger (S7c-A1).
- **Pledge / privacy:** #1151 wide-event-receipt consent as the first permission-chain step; #1160
  census kind table + `ID_MARKERS` projection; #1167 one consent key per (rule, action) + `enables`;
  #1147 TalkBack-study node fields; #1148 coalescer + window selection; #1152 Uber overlays.
- **Recognition / state:** #1114 8.97.8 Compose offer card; #1104 outcomes on transition evidence;
  #1123/#1122 dropoff_pre_arrival vs workflow sheet; #1093/#1149 bind shortfall + owner resolution +
  label horizon; #1102 tap-that-never-landed throttle; #1107 drop-off steps wrapper; #1095/#1078
  close-edge tripwire + absorbed retire; #1073 receipt coverage; #1057 odometer fix gate; #1053
  RE2J rule regexes (ADR-0010); #1054 timer identity + recovery hygiene; #1033/#1029 settle gate +
  receipt re-price; #1030/#1036/#1037 health + parse-shortfall census.
- **Product:** #1024 three destinations (Today · hub · Playbook); #981 weekly plan + Sunday grading;
  #977/#979/#983 Today, heatmap/leaderboard, Time tab; #969/#970/#973/#975 hub redesign; #991 TTS
  recovery; #942 formatting SSOT; #96/#97/#98/#99 MAD Phase 6 feature modules (complete).
- **Infra / quality:** #1162 `NewApi` vital lint across modules; #1066 build id in the logs; #941
  negative corpus + loud corpus reads; #937/#909 liveness family; #907 release lint in PR CI.
