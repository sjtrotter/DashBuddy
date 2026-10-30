status: review
issues: #314

- **🆕 NEW — the analytics read-model projector now folds `app_events` into durable records (#314 PR2). READ THE DB / LOG AFTER THE FIRST DASH POST-UPDATE.**
  The projector runs on `GigLogApplication` startup (not debug-gated) and event-sources the
  `app_events` log into `delivery_records` / `session_records` / `offer_records`. On the **first
  launch after installing this build** it backfills the entire existing log; watch the INFO log for a
  single line tagged `Analytics`: `Analytics backfill complete: N events → D deliveries, S sessions,
  O offers` (counts only — it must carry **no** store/customer names). How to tell it's working:
  after a dash, read the db — `delivery_records` should have one row per completed delivery with
  `realizedPay`, `realizedMiles` (odometer partition delta), `frozenCostPerMile`/`costBasis`
  (`OFFER_FROZEN` when the offer was evaluated, else `CURRENT_FALLBACK`), and `netProfit`;
  `session_records` should have one row per dash whose `deliveries`/`jobsCompleted`/offer counts and
  `lastOdometer − startOdometer` miles match your memory of the dash. Editing the economy settings
  must **not** change any already-stored `frozenCostPerMile`/`netProfit` (they're immutable facts).
  (#314 PR2 — projector/backfill/frozen-economy.)
  - Confirmed: 0/2
  desk: desk-verifiable: compare app_events with projected delivery/session/offer rows and frozen costs before/after an economy-setting change on a replay copy.
  Triage source: first-line blame 2026-07-03 (`b18cd78d9`, original README L1972).
