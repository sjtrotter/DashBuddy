# Analytics roadmap — from the current app state to analytics on the home screen

**Status:** approved 2026-07-03. Epic [#320]; read-model [#314], hub [#315], ratings [#316],
glance [#318], CSV [#319]. This doc is the sequencing reference the analytics build follows.

## Diagnosis — one missing layer

Almost everything is already in place: the design is concrete (#320 handoff), the
`:core:designsystem` analytics kit (`AppStatTile`, `AppBarChart`, `AppSegmented`, `AppGaugeRing`, …)
is **built but unused**, `DashboardViewModel` already computes live session flows and discards them,
`UserEconomy.operatingCostPerMile` is a pure, reusable cost model, and every delivery's realized
pay / tips / timestamps / mileage is **already durably logged** in `app_events`
(`DeliveryPayload` incl. `dropRealizedPay` #528; `metadata.odometer`). The single missing layer is a
**durable read-model** — `CrossPlatformRegion.PeriodTotals` is declared but never populated (the
stepper comments *"computed from DB aggregation … does not exist"*). Build that one layer, connect
the ready pieces.

## The pipeline (current → home-screen analytics)

```
app_events (durable, rich)          EXISTS  DeliveryPayload{totalPay,parsedPay,dropRealizedPay,
  │  realized pay, tips, timestamps          timestamps,hashes}, SessionStopPayload, metadata.odometer
  ▼
① PROJECTOR (event-sourced fold)    BUILD   incremental on new events + one-time backfill;
  │                                          reuses the NetProfit SSOT helper (shared w/ OfferEvaluator)
  ▼
② READ-MODEL tables (Room v9)       BUILD   delivery_records / session_records / offer_records
  │  + period rollups                        + first-class platform column; finally populates PeriodTotals
  ▼
③ AnalyticsRepository (:core:data)  BUILD   today / this-dash / week / all × per-platform → Flows
  ▼
④ ViewModel + UiState               WIRE    DashboardVM (home glance) + AnalyticsVM (hub)
  ▼
⑤ Compose cards                     WIRE    built-but-unused App* kit → designed layouts
```

Two facts drive the sequencing:
- The **live current dash** needs none of ①–③ — the home glance can show this-dash True Net /
  Net $/hr / miles from flows the ViewModel already has. Ship it immediately.
- **Everything historical** (today/week/lifetime, the hub) is gated on the read-model — the keystone.

## Phases

**Phase 1 — Visible now, zero data-layer work.** Home "This dash" glance (3 `AppStatTile`: True
Net, Net $/hr, Miles) + 2×2 entry-tile grid (Analytics/Ratings/Strategy/Economy) wired to
`DashboardViewModel`'s existing session flows; **Ratings screen (#316)** binding the
already-in-state `RatingsSnapshot`; + extract the net-profit math from `OfferEvaluator` into a
shared `:domain` `NetProfit` helper (SSOT, reused by the projector). *(Phase-1 PR:
`feature/analytics-phase1-home-glance-ratings`.)*

**Phase 2 — The read-model foundation (#314), the keystone.**
- 2a economics SSOT helper (lands in Phase 1).
- 2b read-model tables + DAOs (Room v8→v9): `delivery_records` / `session_records` /
  `offer_records` / period rollups, each with a first-class `platform` column.
- 2c the projector: event-sourced fold of `app_events` → records (incremental on
  `DELIVERY_COMPLETED`/`DASH_STOP`/`OFFER_*` + one-time backfill). Realized inputs: pay from
  `dropRealizedPay`/`totalPay`, miles from `metadata.odometer` deltas (#528 Slice B convergence),
  time from timestamps; net via `NetProfit`.
- 2d populate `PeriodTotals` (read-side) + `AnalyticsRepository` exposing period × platform Flows.

**Phase 3 — Real analytics on the home screen.** Swap the Phase-1 live glance to the read-model's
real **today / week / lifetime** totals via `AnalyticsRepository`.

**Phase 4 — The Analytics hub (#315).** `AppSegmented` tabs; **Money first** (true-net waterfall,
earnings bar chart, net $/hr, $/mi, cost breakdown), then Time, Decisions (offer funnel), Patterns
(needs per-leg scoping + stores table #159 — last).

**Phase 5 — Export + polish.** CSV export (#319, deductible miles × IRS rate + earnings); hub
refinements; glance/driving mode (#318, unblocked, can land anytime).

## Locked decisions

1. **Build in `:app`/`:core:*`, not a new `:feature:analytics` module yet** — no `:feature:*`
   module exists; defer the Phase-6 extraction (#97/#315) to avoid paying the modularization tax
   mid-build. Alpha, single-user — extract later.
2. **No separate #202 RFC ceremony** — its core call (event-sourced projection) is already locked;
   the remaining decisions are folded into the #314 build plan: **DB tables** (home needs
   today/week/lifetime that outlive a dash), **defer "leg" scoping** to Phase 4 (period totals need
   only GROUP-BY), first-class **platform column**, **mileage from `metadata.odometer` deltas**.
3. **Front-load the read-model** over the handoff's original phase order (which put HUD + main-app
   refresh first) — analytics is the priority, so #314 moves up behind the Phase-1 quick win.
4. **Economics SSOT** — one `NetProfit` helper (`net = gross − miles × operatingCostPerMile`,
   `$/hr`, `$/mi`) shared by `OfferEvaluator` (estimate) and the projector (realized), so the home
   screen's True Net uses byte-identical cost math to the offer verdict (Principle 5).
