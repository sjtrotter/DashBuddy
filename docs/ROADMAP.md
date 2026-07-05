# DashBuddy Roadmap (living doc)

**Updated:** 2026-07-05 (full 72-issue sweep + triage; analytics hub build day). Buckets reflect
the board reality — when an issue's state changes, move it here or this doc rots. Companion:
`docs/design/analytics-roadmap.md` (the analytics-specific phases).

## Now / in flight (2026-07-05)

- **Analytics hub (#315, epic)** — Money tab v1 MERGED (#662); Decisions tab (H3) + the frozen
  fuel/non-fuel waterfall lift (#659, incl. the `startSource` hydration fix) in build. Remaining
  phases: H4 Time tab, H5 Patterns (needs #159 chains; zone capture doesn't exist), H6 per-day
  earnings chart. All new aggregations MUST use the #655 session-anchored WHERE shape.
- **#653** — identity-less-drop double-count guard (fold-side + upstream firewall parity). Next
  after #659 merges (same fold files).
- **#319 CSV export** — after H3 merges (shares `AnalyticsScreen`). The free-tier pillar item.
- **#650** — per-dash drill-down + corrections-as-events (`MANUAL_DELIVERY`/`PAY_ADJUSTMENT`).
  Consumes `recentSessions`/`deliveriesForSession` + the unattributed callout. Carries two review
  notes: the dangling-sessionId LEFT-JOIN belt, and the correction folds must upsert session rows
  atomically.

## Next up (unblocked, ranked by value × boundedness)

1. **#551** — semantic log levels + PII-safe shareable sink (Principle 7 implementation; the
   export is a privacy surface; reuses `SensitiveTextMarkers` fail-closed). *opus*
2. **#590** — generative property tests over the untrusted-input boundaries (already found live
   fail-open gaps + a CI blind spot historically). *opus*
3. **#419** — rule-count caps + sensitive-rule survival across a ruleset replace (pledge;
   in-app, no repo dependency; #192 prereq). *opus*
4. **#438** — multiplatform correctness pack (per-platform timer keys etc.; unblocks #588;
   advances #585). *opus*
5. **#647** — canonicalizer merge hardening (unknown section keys; flat/dir collision). *opus/sonnet*
6. **#293** — RuleCompiler robustness hardening (complements #647/#590). *opus*
7. **#666** — notification envelope: actionLabels scrub + `textFields()` SSOT anchor. *sonnet*
8. **#660** — null-session gross seam (net > gross possible; decide fix direction when observed).
9. Mechanical batch: **#439** dead-vocabulary cull, **#57** strings.xml extraction (→ #428 i18n),
   **#240/#239/#238** file splits (#237 family), **#244** test relocation. *sonnet*
10. **#105** — Layer-2 OfferPipelineTest. *sonnet*

## Needs design / field data first (actionable-soon)

- **#245** ADR-0007 canonical domain schema — high-leverage; unblocks #141 (cloud RFC) + #251
  (Uber multi-offer) → which unblocks #110 (expanded offer overlay).
- **#526** pickup placeholders + swap (PR #546 is the dev-gated partial; reshaped scope; wait for
  multi-pickup field data). **#630** mid-stack under-attribution (same family). **#527** job
  lifetime. **#528** GPS $/mi slices B/C.
- **#556-family learned models:** #254 auto-tuned time constants; #588 per-platform shop rate
  (blocked by #438).
- **Field-capture-dependent recognition:** #249/#250 (Uber flow signals), #301 (unassign/cancel),
  #550 (pay-adjust toast), #501 (GoPuff deferred branches), #294 (odometer/timer disagreement),
  #337 (validate-then-close).
- **#579** voice accept/decline (design vetted; mic-FGS v1; nontrivial build).
- **#163** spot-save countdown; **#122** min-SDK audit; **#252** ZIP zones; **#84** Play internal
  track; **#56→#99** settings refactor → module extraction.
- **MAD Phase 6 module extractions:** #96 (bubble; blocks #110), #97 (dashboard), #98 (setup).
- **#214** DSL onboarding (tutorial + preview tool; schema done).
- **#505** replay-harness frontier (eval-loopback economics, GoPuff repro, on-device review tool).

## Blocked / parked (with the gate)

- **#246 LEGAL counsel** ← the pacing gate for: #170 (disclosure flow), #637→#636→#638 (matchers
  repo creation — PARKED by the 2026-07-03 keep-in-tree decision), and transitively #641.
- **#416 signature verification** ← gates #640 (OTA fetch). **#419** is buildable now (above).
- **#251** ← #245. **#110** ← #251 + #96. **#99** ← #56. **#588** ← #438. **#141** ← #245.
- **#192 epic** (matchers distribution) — in-tree M1 done (#635/#639); repo-era work parked.
- **#193/#194** academic pillar RFCs — future.

## Recently resolved in the 2026-07-05 sweep

Closed as superseded: #202 (→ #314 plan §7), #106 (→ SessionReplay `reduceMixed`), #338
(→ #461/#531), PR #547 (annotations already 2/2 in the README). Merged: PR #496 (running-$/hr
design doc, reconciled with the frozen-record model). Shipped: #655, #632, #318, retro-findings
fix (#664), Money tab v1 (#662). Filed: #659, #660, #666.
