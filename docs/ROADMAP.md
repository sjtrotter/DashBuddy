# DashBuddy Roadmap (living doc)

**Updated:** 2026-07-05 evening (post analytics-hub completion — H4/H6/#650 merged). Buckets
reflect the board reality — when an issue's state changes, move it here or this doc rots.
Companion: `docs/design/analytics-roadmap.md` (the analytics-specific phases).

## Now / in flight (2026-07-05 night)

- Nothing in flight — the 07-05 night wave cleared the "Next up" top tier (see Recently resolved).
- **Analytics hub (#315, epic)** — Money (H1/H2/H6 incl. earnings-by-day chart + header CSV
  action), Decisions (H3), and Time (H4) tabs are ALL LIVE. The only remaining phase is
  **H5 Patterns**, blocked on #159 (chains; zone capture doesn't exist). All new aggregations
  MUST use the #655 session-anchored WHERE shape.

## Next up (unblocked, ranked by value × boundedness)

1. **Design session (needs the dev):** #438 items 5/6/7/8/9 (D3 stepper edges, per-platform grace
   seam, D11 per-platform offer slots, actuation identity, odometer arbitration) co-designed with
   **#245 ADR-0007 + #251** — the scoping comment on #438 has the split rationale. Item 9 carries a
   product decision (overlap-mile attribution, feeds #528).
2. **#660** — null-session gross seam (net > gross possible; decide fix direction when observed —
   dev-observation-gated). The #675 per-day chart is a consumer of the same seam (noted on the issue).
3. Mechanical batch: **#439** dead-vocabulary cull, **#57** strings.xml extraction (→ #428 i18n),
   **#240/#239/#238** file splits (#237 family — #239 now unblocked by #293's hardening landing
   first per the old sequencing note), **#244** test relocation. *sonnet*
4. **#105** — Layer-2 OfferPipelineTest. *sonnet*
5. **#590 Phase-2 fuzzing** — stays gated on #416/#192 (the only remainder of #590).

## Needs design / field data first (actionable-soon)

- **#245** ADR-0007 canonical domain schema — high-leverage; unblocks #251
  (Uber multi-offer) → which unblocks #110 (expanded offer overlay). (Formerly also gated #141,
  closed 2026-07-12 — its un-built cloud half folded into the #192/#193/#194 pillar epics.)
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
- **#251** ← #245. **#110** ← #251 + #96. **#99** ← #56.
- **#192 epic** (matchers distribution) — in-tree M1 done (#635/#639); repo-era work parked.
- **#193/#194** academic pillar RFCs — future.

## Recently resolved in the 2026-07-05 sweep

**Night wave (the security/hardening run):** #679 (#551 P1 — INFO+ leak fixes + level/tag taxonomy);
#680 (#590 PR1 — kotest-property + compile-recursion/ReDoS-budget/notification-totality, all
red-first); #681 (**#419 CLOSED** — rule caps + structural sensitive-rule survival; `overrideable`
made real); #682 (#438 PR A — per-platform timer keys/screen-target, post-merge uniqueness,
Unknown-region filter; items 5-9 deferred to design); #683 (**#647 CLOSED** — canonicalizer
fail-loud guards); #684 (**#293 CLOSED** — RuleCompiler robustness incl. opt-in `isolable`
per-rule isolation); #685 (#590 PR2 — marker-evasion hardening: 10/14 classes were live; INFO+
replay gate; fill-ins); #686 (**#551 CLOSED** — the PII-safe shareable log sink, fail-closed
scrub at the sink).

**Evening wave:** #653 (phantom double-count guard, #673); #315 H4 Time tab (#674); #315 H6
earnings-by-day chart + hub-header CSV action (#675); **#650 CLOSED** — per-dash drill-down
(#676) + corrections-as-events `MANUAL_DELIVERY`/`PAY_ADJUSTMENT` (#677; fable review caught an
uncosted-MANUAL net-drop HIGH + a liveness-inflation MED pre-merge). Deferred from #650:
session-scoped "categorize a bonus" (needs a v11 display-only table).

**Day wave:** Closed as superseded: #202 (→ #314 plan §7), #106 (→ SessionReplay `reduceMixed`),
#338 (→ #461/#531), PR #547 (annotations already 2/2 in the README). Merged: PR #496
(running-$/hr design doc, reconciled with the frozen-record model). Shipped: #655, #632, #318,
retro-findings fix (#664), Money tab v1 (#662), Decisions tab (#669), #666 NotifTextField SSOT
(#670), #659 4-step waterfall (#668/#672), #319 CSV export (#671). Filed: #659, #660, #666.
