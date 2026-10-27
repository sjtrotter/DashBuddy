## 2026-10-05 (desk analysis of the 10-05 pull — the 10-01→10-04 dashes across seven builds; money reconciles except one #1120-class receipt split; #1197 health, #1185 identity and the #1200 device half pass; the first server-side census checks from the desk)

**Date:** 2026-10-05 (pull) · **Platform(s) tested:** DoorDash (8.99.20 → 8.100.12 between the 10-03 and
10-04 dashes); one 1-minute Uber session early 10-03 · **Branch under test:** `master` at `9b7e2c68` (10-01
dashes), `f98fd7c9`/`067f072a` (10-02 night), `a41f0965` (10-03), `1e7e5fcf` → `e35dee8f` (10-04 — the #1200
trusted-envelope uploader ran the 10-04 dash), `61e66323` (installed 10-05 morning, no dash). · **Field
conditions:** written at the desk from the pull per CLAUDE.local.md § Field-data import (1 503 captures,
20 logs, DB+WAL; device purged) plus the FIRST server-side reads over the new workstation WireGuard tunnel
(`/ops/ledger`, `/ops/health`, `/ops/installs`, `/ops/clusters`). Sessions / drops: 10-01 2 / 3; 10-03 4 / 8;
10-04 3 / 5. Offer cards ↔ `OFFER_RECEIVED` 1:1 (18 / 21 / 9), one outcome per offer. Zero ERROR on any
build. Dasher-banking sweep over `captures/`: **0 hits**. Full report: `~/dashbuddy/logs/2026/10/05/desk-2026-10-05.md`.

### Confirmations that moved checklist items

1. **#1197 daily recognition health — 1/2 (desk + server, 10-05).** Server `fleet` row 2026-10-04 · doordash ·
   8.100.12: admitted 544, unknown 78, trips 0, a full `ruleCounts` map; device `healthPosted=1`, no health WARN.
2. **#1185 status line + identity — 1/2 (dev eyes 10-05 "working properly" + desk).** One enrolment, no
   unusable/revoked/unauthorized line; the server shows one install, trusted, not revoked, last seen 10-05.
3. **#1200 trusted-envelope uploader — 1/2 (device half, 10-04 on `e35dee8f`).** `envelopesPaired=70 Unpaired=0`,
   `Held/Spooled/Posted = 70/70/70`, zero `not_trusted` — the share switch was already ON for the 10-04 dash.
   Server half (a wireframe on a cluster from that dash) still to be eyeballed.
4. **#1148 bubble-as-active-window — desk half 2/2, retired.** Second clean desk pass (first 09-30). Item 1
   (dev eyes: the HUD keeps updating while you touch it) was never deliberately exercised; it rides along.
5. **#1147 richer node fields — 2/2, retired.** Items 2–3 pass: the offer card's Accept/Decline LABEL nodes carry
   no click flag while their clickable parent does (the #1149 owner walk is the right reader); no rich-field
   value sits raw beside redacted text.
6. **#1165 dash-controls sheet → `timeline` — 1/2.** All 7 frames recognized as `timeline`, all while Online.
7. **#1167 one consent per action — 1/2 (log half).** 11 notification Decline taps each resolved `OFFER_DECLINED`
   on the matching offer hash (the #438 B4 check). `confirm_decline` is GRANTED since 10-03 and skipped only because
   quick declines are off in settings (a fact, not a to-do — dev standing choice).
8. **#1149 label re-find — 1/2 with a caveat.** 13 receipt expands, 12 clean, one stale abort when an offer card
   covered the receipt (the abort is the designed outcome).
9. **#1104 fast decline — 1/2 with a caveat.** The one `OFFER_TIMEOUT` beside a `Denied confirm_decline` (10-01
   14:47) landed ~0.3 s before the card's own countdown end — by design, not a lost decline.
10. **#1122 drop-off sheet masking — check 1, 1/2.**
11. **#1182 uploads — PARTIAL (already retired at 2/2):** device uploads per UTC day 10-03 179 / 31 batches,
    10-04 73 / 21, 10-05 2 / 2 at log end; the server ledger shows TODAY only (14 / 5, nothing rejected — the
    other 12 uploaded after the pull), so earlier days cannot be cross-checked from the ledger endpoint.

### Bugs / hypotheses (recorded, not fixed)

1. **H1 — 18.42 unattributed on the 10-03 10:18 dash (`a41f0965`): the #1120 receipt-split class.** Job 836 took
   three accepts from the same store; drop 837 never showed its receipt (an offer card followed), its completion was
   held to the job close, and the ONE 20.03 receipt was split 10.02 / 10.01 across both drops; a
   `JOB_ACCEPT_MISMATCH` fired. Likely the same mechanism as #1120 (single-drop receipt applied across a job) under
   the #810 Tier-1 inconclusive shape. Evidence posted on #1120.
2. **H2 — unassigning through the support CHAT records no `TASK_UNASSIGNED`.** Twice on 10-03 (~11:09, ~13:11):
   accept → help → resolution options → virtual-assistant chat; that path never renders the "been unassigned" text
   the rule anchors on, so the abandoned accept stays on the job (it feeds H1's mismatch) and one was never resolved.
   One possibility: a `task:unassigned` anchor on the chat-flow's confirmation surface, capture-gated. Posted on #301.
3. **H3 — #1119 again: two real accepts recorded as `OFFER_TIMEOUT`** (seq 3693, 3702), each followed within a
   second by a pickup and a paid drop. Posted on #1119.
4. **H4 — census uploads paused five hours on 10-04:** from 14:18:59 the worker deferred and re-checked hourly until
   19:19 CDT (UTC midnight) — the shape of the daily skeleton budget. Only `BudgetExhausted` / `RateLimited` can
   defer, and the `census deferred runs=1` line does not say which. No data lost. Filed: the deferral line should
   name its cause and the deadline (issue below).
5. **#1108 FAIL (still open):** seq 3667 carries `realizedMinutes = −32.4` (10-03, stacked job 836) — the same drop
   as H1, apparently timed from the job close rather than its own completion. Evidence posted on #1108.

### UNKNOWN census + corpus-intake TODO

470 UNKNOWN envelopes in 40 families; the largest are the Uber home screen (62) and the DoorDash side-nav/map host
(57) — the latter is the "blank Compose host" class the dev saw on the dashboard (1 sighting of the exact
6-node frame; classified `noise` is sufficient, no rule). PII recipe sweep: 0 first-name-plus-initial, 0 PIN/gate
codes, 0 customer notes, 0 fused name nodes; 7 street-address hits are merchant addresses on offer cards. **The
two-capitalized-words name check is unfinished** (the analyst's value-listing command was blocked by the PII
permission rule, correctly): 62 UNKNOWN files need the MANUAL sweep before any corpus intake — start with the
drop-off step-instructions family (7) and the `message_input` click (the #919 class).

### Meta

- First pull analysed with the server half read from the desk (tunnel + operator token, no AWS): the ledger is
  today-only, so per-day cross-checks need either `/ops/ledger?day=` history on the server or the device counts
  alone. Noted in the playbook's census table.
- `confirm_decline` denials ×15 on 10-01 predate the grant; none since. Quick declines are off by choice.
