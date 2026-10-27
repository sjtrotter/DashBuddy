## 2026-09-30 (smoke test of `1fe9243f` — the TalkBack batch + census build goes live; an 18-second dash; the "Current dash" sheet is UNKNOWN)

**Date:** 2026-09-30 · **Platform(s) tested:** DoorDash (app **8.99.20**) · **Branch under test:** `master`
at `1fe9243f` (build `0.230.0+1fe9243f`, installed 18:52) — the first run of PRs #1150 (#1148), #1153
(#1149), #1155 (#1152), #1156 (#1151), #1158 (#1147), #1160 (#1145) and #1163 (#1161/#1162). · **Field
conditions:** desk smoke test, no driving: install → the new wide-event-receipt consent step allowed →
DoorDash opened and browsed (side menu, ratings, notifications, performance detail) → one dash started
19:04:11 and ended 19:04:29 (`early_offline`, no summary screen, no offer). One process, no recovery,
**zero ERROR**, two WARN (both expected, below). 35 capture envelopes, 1,065 log lines. Pull dir
`~/dashbuddy/logs/2026/09/30`, device purged after verification. The 09-28 dashes in the db were already
covered by the 09-29 pull. **Consent (corrected after the dev's read):** the grant store SURVIVED the reinstall and every current key is decided — `expand_earnings` GRANTED, `decline_offer` GRANTED on the legacy View-card branch but DENIED on the Compose-card branch (the one 8.99.20 renders), both `accept_offer` branches DENIED, `confirm_decline` DENIED (by choice). The first write-up said "ungranted" because the reconcile INFO line is a fixed string that always reads "none granted" — #1167(b). The duplicate Accept/Decline rows the dev asked about are the two `offer_popup` branches — #1167(a).

### Bugs

1. **The 8.99.20 timeline sheet ("Current dash") falls to UNKNOWN on a per-offer dash with no task, and its `End dash` tap is an UNKNOWN
   click (#1165, new — the dev identified it as the existing `timeline` surface: the rule's `Dash ends at`/`Current task` anchors rotted and the `This dash` figure is now a glyph wheel).** The Compose sheet (`Current dash` / `Pause orders` / `Earnings` / `This dash`
   `$0.00` wheel / `End dash` / `Other` / `Go to home screen` / `Read instructions on arrival`, 52 nodes,
   ids only `action_bar_root`/`content`) has no rule; the confirm sheet after it IS recognized
   (`end_dash_confirm`), so the dash still ended correctly. Likely cause: the sheet is new chrome since
   the dash-lifecycle rules were written; one possibility is a recognize-only rule on its text anchors
   with an optional, settle-gated `This dash` read — the issue carries the shape.
   - **Status:** Shipped in #1165's PR (2026-09-30) — a third `require` arm; the figure parsed as `sessionEarnings 0.0` on the fixture.

### Verification TODOs → checklist movements

2. **#1151 wide-event-receipt consent — 1/2.** The step appeared before the accessibility card and was
   allowed; the log reads `Event receipt: wide=false` at connect (18:52:35) then `wide=true` (18:53:43),
   and `WINDOWS_CHANGED (coalesced n=…)` DEBUG lines begin right after. Items 3–7 (release decline, debug
   decline shell, the switch) not exercised.
3. **#1148 coalescer — 1/2 (desk half).** 132 `DRIP` lines follow the cadence contract: a leading
   `n=1 span=0ms` on every idle→change edge (43 of them), floods coalesced at `n=19 span=202ms` /
   `n=24 span=302ms`, trailing drips at 100–250 ms. No `Skip active window` line in the run, no Mapper
   WARN. Item 1 (frames while the bubble is the active window) was not deliberately exercised; no
   `PipelineStats` summary printed (the run was shorter than one summary interval), so
   `mappingFailures` is inferred from the absence of WARNs, not read.
4. **#1147 rich node fields — 1/2.** Across the 35 envelopes: `clickAction` ×282, `visible` ×49,
   `tooltip` ×3, `pane` ×2 (so a DoorDash surface DOES publish a pane title). The PII sweep over
   `text/desc/hint/pane/clickLabel/uid/tooltip` found only chrome ("Pro Shopper", "Side Menu", "Earnings
   Mode Switcher", "Dash Preferences", "Open Settings"); average envelope 88 KB vs 129 KB on the 09-29
   pull (different surfaces, but no growth). No offer card this run (item 2 open).
5. **Not exercised:** #1160 chat-list masking (no chat), #1149 receipt re-find (no delivery), #1152 Uber overlays (Uber off).

### Field UX context

6. The two WARNs: `ParseHealth: Rule doordash.screen.idle_map matched with a parse shortfall` — the
   documented benign baseline (idle_map's one field is legitimately optional, 01-sensor-pipelines) — and
   `Effects: Timer Expired: GRACE_COMMIT`, the session-end grace committing the 18-second dash as
   `early_offline` with every summary scalar null (`SessionEndedFields.totalEarnings` null, not `$0.00`
   — #1030 holding).
7. UNKNOWN census (8 window + 1 click): the #1165 sheet (2 frames incl. its mid-inflate 21-node form),
   five text-less Compose shells mid-inflate (9–21 nodes — side-nav host, a `progress_bar` workflow host),
   and one 48-node home frame carrying `earnings_pill` ("This week" + a glyph wheel) and
   `side_menu_button` but not yet the `Earnings Mode Switcher` description the `idle_map` rule anchors on
   — a single transitional frame; the next six frames were `idle_map`.
