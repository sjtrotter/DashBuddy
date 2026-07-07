# #159 — Stores table + store entity resolution in the read-model

**Status:** dev-decided 2026-07-07 (design session); **three** adversarial design-vet passes
applied same day, narrowing each round (BLOCKER → BLOCKER → HIGH). Pass 1's F1 BLOCKER (the per-job
fold accumulator can't survive the projector's per-batch context rehydration) drove
resolve-from-rows. Pass 2's B1 BLOCKER + H1 (the first revision took the running key from the
trigger event → payout-less `DASH_STOP` downgraded every keyed store; re-resolution undid driver
corrections) drove the row-sourced key + pinned corrections. Pass 3's B2 HIGH (per-drop key
extraction discarded sibling-store keys in a stack — the Target+Maple case) drove persisting the
**full receipt** store-form set on the row + all-anchor matching. All findings in the resolution
table at the bottom; pass 3 declared H1 + M1–M5 + L1–L2 converged. **Note (tiering):** the
design/vet loop ran at the Opus fallback tier because Fable was unavailable this session; a Fable
confirmation pass before build is the ideal, per the project's reserve-Fable-for-design policy.
**Blocks:** #315 Patterns tab (store report card). **Related:** #554 (shadow projector, the
design proof), #526-family (stacked attribution), #655 (session-anchored bucketing), #314
(read-model), #688/#691/#703 (fold provenance).

## Problem

DoorDash shows the same physical store under different surface forms — offer `Target`, pickup
`Target`, dropoff/payout `Target (02426)`; offer `Maple Street Biscuit Company`, payout
`Maple Street Biscuit - Alamo Ranch` (06-19 field data on the issue). Today's per-store
analytics `GROUP BY storeName` on `delivery_records` — a raw-string match, so one store
fragments across its representations and cross-surface attribution falls back to string
matching. #159 makes the store a first-class, resolved entity.

## Grounded facts the design rests on

- **The resolution logic already exists and is field-verified.** `StoreChainProjector`
  (`:domain`, #554) resolves offer → pickup → dropoff → payout per job: pickup names are the
  canonical anchors; other surfaces attach by brand-token match; `runningKey` is extracted
  from payout forms (`(02426)` digits and ` - Area` suffix). It runs shadow-only today
  (`ShadowStoreChainLogger`, log-and-discard).
- **The event log backfills everything except the offer↔job link.** `OfferPayload` carries
  the full `ParsedOffer` (offer-side store names); `PickupPayload` carries `storeName` +
  `phaseStartedAt/arrivedAt/confirmedAt` (pickup dwell is already in the log);
  `DeliveryPayload` carries dropoff `storeName`, `customerHash`, `addressHash`, and the
  payout `parsedPay` (the running-key carrier). No logged payload links an offer to a job.
- **The enrichment must run job-side, not offer-side.** `OFFER_ACCEPTED` fires at the
  leave-of-presentation edge; the job is minted later at the task edge
  (`acceptInputsFromPending`), so a `jobId` cannot be stamped onto `OfferPayload`. The
  runtime job carries `parentOfferHashes`, so the exact link is available to every
  pickup/delivery event emit.
- **Store address is parsed but never logged** (`Task`/`ParsedFields` carry it;
  `PickupPayload` doesn't), so address is future-events-only.
- **Read-model invariants:** rebuild ≡ backfill (a `PROJECTOR_VERSION` bump refolds from
  zero and must reproduce identical rows); records are REPLACE-idempotent keyed on source
  `sequenceId`; frozen economics are never recomputed; Room migrations are additive-only
  (current: Room v11, projector v4).

## Dev decisions (locked 2026-07-07)

| # | Decision | Choice |
|---|---|---|
| D1 | Stats shape | **Visits table + derive-at-read.** `stores` holds identity/resolution only; a new per-pickup read-model table carries the raw visits; dwell/counts/percentiles are `GROUP BY` queries (same pattern as period economics). No accumulator columns — SSOT, and p50/p95 can't be folded incrementally anyway. |
| D2 | Identity | **Deterministic TEXT `storeKey`** = `platform + "|" + normalizedChain + "|" + runningKey` (empty key segment while unknown; platform segment added post-vet, F5 — a McDonald's on two platforms must not collide into one row, and single-platform field testing would mask it). Refold-stable by construction; FKs carry the key; reads coalesce chain-wide via `normalizedChain`. No autoincrement id, no separate alias table (v1). |
| D3 | Offer↔job link | **Enrich + temporal fallback.** Exact link on future events (job-side, see below); the fold falls back to a session-scoped temporal join for historical events. |
| D4 | Scope riders | **In:** `storeKey` FK columns on `delivery_records`/`offer_records`; `storeAddress` into `PickupPayload`; runningKey extraction hardening (the #554 polish list). **Deferred to phase 2:** evaluator wait-time pre-enrichment (#315 Patterns is the blocking consumer, not the evaluator); OTA chain-normalization lookup table. |

## Schema (Room v11 → v12, additive-only)

### New table: `stores`

Identity/resolution only (D1). One row per resolved store entity; a **chain-only provisional
row** (`storeKey = "heb|"`) exists while no running key has been observed and coexists with
keyed rows once one is learned — reads that want chain-level rollups group by
`normalizedChain`, reads that want per-location detail group by `storeKey`.

| column | type | notes |
|---|---|---|
| `storeKey` | TEXT PK | `platform + "\|" + normalizedChain + "\|" + runningKey` (`runningKey` segment empty while unknown; platform segment prevents a cross-platform chain collision, F5). Deterministic — a refold reproduces it byte-for-byte. |
| `platform` | TEXT | `Platform.wire` from session context (P8: never id-parsed, never a literal); also the first key segment. |
| `normalizedChain` | TEXT (indexed) | Lowercased, whitespace-collapsed, ` - {Location}` / ` #{number}` / `({…})` qualifiers stripped — the issue's `offer_name_normalized`, computed by one `:domain` normalizer shared with the resolver. |
| `chainDisplay` | TEXT | First-observed capitalization of the chain form (issue's `chain_name`). |
| `runningKey` | TEXT nullable | The platform's location discriminator (`02426`, `Alamo Ranch`, `0164-0045`). **Named `runningKey`, not `doordash_key`** — P8: the column is platform-neutral; the extraction patterns are surface-shape heuristics (parenthetical codes, ` - ` suffixes), not platform-gated logic. |
| `offerNameForm` | TEXT nullable | First-observed offer-surface form (`Target`). |
| `pickupNameForm` | TEXT nullable | First-observed pickup/canonical form. |
| `payoutNameForm` | TEXT nullable | First-observed payout form (`Target (02426)`) — the key carrier, kept for audit/re-resolution. |
| `address` | TEXT nullable | First observed non-null `storeAddress` (from the enriched `PickupPayload`; null for all historical rows). Merchant data, not customer PII — fine at rest, never in INFO+ logs (P7). |
| `firstSeenAt` / `lastSeenAt` | INTEGER | Maintained by the fold from `obs`-derived event timestamps (never wall clock). The one denormalization kept on the row (cheap, deterministic, saves a MIN/MAX join on every list read). |

No stats columns (D1): `match_count`, `pickup_count`, `avg/p50/p95_wait_ms` from the issue's
original schema are all **derived at read time**.

### New table: `pickup_records`

One row per completed pickup — the visits table (D1). Folded from `PICKUP_CONFIRMED` (the
closing event of the pickup phase, carrying the full `phaseStartedAt/arrivedAt/confirmedAt`
progression). A pickup that never confirms folds no row (no dwell to report).

| column | type | notes |
|---|---|---|
| `eventSequenceId` | INTEGER PK | Source `PICKUP_CONFIRMED` row in `app_events` — provenance AND idempotency, exactly like `delivery_records`. |
| `sessionId` | TEXT nullable (indexed) | `app_events.aggregateId`. |
| `platform` | TEXT | From session context. |
| `jobId` / `taskId` | TEXT | Slot identity. |
| `storeName` | TEXT | Raw pickup-surface form (the canonical anchor). |
| `storeKey` | TEXT nullable (indexed) | Resolved entity — stamped/re-stamped by resolution (see fold design). |
| `phaseStartedAt` | INTEGER | |
| `arrivedAt` / `confirmedAt` | INTEGER nullable | Dwell = `confirmedAt − arrivedAt`, derived in SQL, never stored. |
| `deadlineMillis` | INTEGER nullable | |
| `activity` | TEXT nullable | Shop vs pickup — dwell populations differ; the report card can segment. |

### New columns (all nullable, additive)

- `delivery_records.storeKey TEXT` (indexed) — the resolved entity key, stamped/re-stamped by
  resolution; the per-store economics query groups on it (falling back for unresolved rows).
- `delivery_records.payoutStoreForms TEXT` — **the persisted receipt evidence (B1 fix + the
  multi-store correction).** When a `DELIVERY_COMPLETED` carries `parsedPay`, the fold stamps the
  **full set** of the receipt's store forms (every `parsedPay.customerTips[].type` — e.g.
  `["Target (02426)", "Maple Street Biscuit - Alamo Ranch"]`, serialized) onto **that one row**,
  null on the sibling drops that carried no receipt. Critically it persists **all** the receipt's
  store lines, not just this drop's own — because a stacked job settles on **one** end-of-job
  receipt that rides a **single** completion (`DeliveryCompletionEffects` gates `parsedPay` to the
  announced drop; siblings carry `dropRealizedPay` only, `parsedPay = null`), so per-drop
  extraction would discard every *other* store's running key (the Target+Maple case #159 exists to
  fix). Resolution reads this set from rows and matches **each** pickup anchor against it — the
  shadow resolver's all-lines match (`StoreChainProjector` iterates the whole payout), now
  **row-sourced** so a payout-less `DASH_STOP`/add-on re-run recomputes the same keys instead of
  downgrading them. Merchant strings — fine at rest, **never** an INFO+ log surface (P7).
- `delivery_records.storeKeyPinned INTEGER` (0/1, default 0) — **driver-correction sticky bit
  (H1 fix).** Set when a `DELIVERY_ADJUSTMENT` supplies a `newStoreName`; resolution **skips
  re-stamping any pinned row**, so a correction that *disagrees* with the pickup anchor is not
  silently re-keyed back to the anchor store. The row's grouping is then driver-owned
  (read-side `normalizedChain(storeName)`, F9). **Use this dedicated bit — do NOT overload
  `originalStoreName`/`USER_CORRECTED`**, which a MANUAL row also carries (ambiguous). The pin is
  derived from the `DELIVERY_ADJUSTMENT` event, so a from-zero refold re-derives it (and the F8
  wipe clears it, then the replayed event re-sets it) — refold-deterministic.
- `offer_records.storeKey TEXT` — the *primary* store of the offer (first order's hint) for
  single-store offers; null for multi-store offers in v1 (a per-order offer↔store bridge is
  phase 2 if a consumer needs it).
- `offer_records.linkedJobId TEXT` (indexed) — **the persistent claimed-offer set (F4).**
  Stamped at resolution when an offer is linked to a job (exact via `jobOfferHashes`, or the
  brand-token-guarded temporal fallback). Survives batch boundaries and is refold-deterministic,
  so the temporal fallback's "not already claimed" test is a durable `linkedJobId IS NULL OR =
  thisJob` predicate rather than an ephemeral in-memory set.

### Migration checklist (per CLAUDE.md `:core:database` posture)

Bump `DashBuddyDatabase.VERSION` 11 → 12 → regenerate exported schema JSON → `AutoMigration`
(pure additive: two new tables + `delivery_records.{storeKey,payoutStoreForms,storeKeyPinned}` +
`offer_records.{storeKey,linkedJobId}` + `pickup_records.storeKey`, all nullable / defaulted) →
`MigrationTestHelper` case in `core/database/src/androidTest` →
`SchemaVersionGuardTest` chain v8→12 intact.
`PROJECTOR_VERSION` 4 → 5: wipe + refold populates `stores`/`pickup_records`/`storeKey`
columns for **all history** (rebuild ≡ backfill — the backfill is just the first drain).
**F8:** the `rebuildIfVersionChanged` wipe (`AnalyticsProjector`, today deletes
`deliveries`/`sessions`/`offers`) MUST also `deleteAllStores` + `deleteAllPickupRecords` on a
version bump — otherwise stale `stores` rows survive and their first-observed fields become
permanently wrong. One line, but exactly the forgotten-edge class the data-safety posture exists
to catch.

## Fold design (`AnalyticsProjector` + `RecordFolds`) — **resolve-from-rows**

> **Post-vet redesign (F1 BLOCKER).** The first draft accumulated each job's surfaces in a
> per-`jobId` `SessionFoldContext` field and resolved at job close. That does **not** work on
> the live path: `AnalyticsProjector.foldBatch` builds `contexts` **locally per batch** and
> rehydrates `SessionFoldContext` from the record tables each drain, so a job whose
> `OFFER_ACCEPTED`, pickups, and closing completion land in different drains (the normal case
> during a live dash — every event wakes a drain) would resolve against an **empty**
> accumulator. A from-zero refold (job usually intact in one 500-event batch) would produce
> rich rows; live operation would produce empty ones — a direct violation of rebuild ≡
> backfill. **There is no per-job accumulator.** Resolution reads the job's own
> **already-committed rows** instead — the same in-transaction, read-fresh-by-key pattern the
> projector already uses for `applyDeliveryAdjustment` (`AnalyticsProjector.kt`).

The resolver joins the existing fold — same drain loop, same single `db.withTransaction`
per batch, same watermark. No second projector, no second watermark, **no new fold-state table.**

1. **New: `PICKUP_CONFIRMED` folds a `pickup_records` row; `DELIVERY_COMPLETED` persists the
   receipt evidence.** Add a pure `RecordFolds` branch that emits a `pickup_records` row from
   the `PickupPayload` (`storeName`, `arrivedAt`/`confirmedAt`, `activity`, taskIds), `storeKey =
   null` at write time; a pickup that never confirms folds no row (F10 — an arrived-then-cancelled
   visit is a known v1 gap, not a silent one). **And (the B1 fix):** the existing
   `DELIVERY_COMPLETED` fold, when the payload carries `parsedPay`, writes the **full set** of the
   receipt's store forms (`parsedPay.customerTips[].type`) to `delivery_records.payoutStoreForms`
   on that one row — **all** the receipt's store lines, not just this drop's, because a stacked
   job settles on one end-of-job receipt riding a single completion (the sibling drops carry
   `parsedPay = null`), so per-drop extraction would silently discard every *other* store's
   running key. The receipt evidence is now a **persisted row fact**, captured at the one event
   that carries it — never re-derived from a trigger event again.
2. **Resolution trigger → a `StoreResolution` task carried into the transaction.** In the fold
   loop, `RecordFolds` emits a `StoreResolution` (jobId, sessionId, platform, the job's
   `offerHashes` from the enriched payload or empty) — collected into a per-batch `resolutions`
   list like `deliveries`/`offers`. **Note: the payout is NOT threaded** (that was the B1 defect);
   resolution reads the receipt forms from `delivery_records.payoutStoreForms` instead. **Triggers
   (exhaustive, F3):** every `DELIVERY_COMPLETED` of the job, `DASH_STOP` session close, **and**
   the inferred-close path (`inferredCloseOpenSessions`). A session-level trigger enumerates its
   jobs via `SELECT DISTINCT jobId … WHERE sessionId = ?` (L2). Resolution is **re-runnable**: a
   later trigger re-resolves deterministically from the current row set — and because the receipt
   evidence is row-sourced (step 1), a payout-less final `DASH_STOP` run computes the **same** keyed
   `storeKey` for **every** store a prior payout-bearing run did, never downgrading any of them.
3. **Resolution runs inside the transaction, in a fixed order (M1/M2).** The transaction sequence
   is explicit: **`deliveries` upsert → `pickups` upsert → `sessions` upsert →
   `applyDeliveryAdjustment`/`applyPayAdjustment` (sequenceId-ordered) → `resolutions`
   (sequenceId-ordered) → watermark.** Pickups strictly before resolutions (so a same-batch
   pickup is an available anchor); adjustments before resolutions (so a same-batch store
   correction is honored). For each `StoreResolution`:
   - Query the job's committed `pickup_records` (anchors + dwell) and `delivery_records` (dropoff
     names, customer hashes, `payoutStoreForms`, `storeKeyPinned`) **by `jobId`**. Prior-batch rows
     are committed; this-batch rows were upserted earlier in this transaction — all queryable
     regardless of the batch boundary (this is what makes incremental ≡ refold).
   - Run the **row-shaped resolver** (see M5 note) over: pickup anchors, dropoff rows, and the
     **union of `payoutStoreForms`** across the job's `delivery_records` — queried `ORDER BY
     eventSequenceId` so the union order is stable. On the fielded single-receipt shape exactly one
     row is non-null, so the union is that one set and matching is unambiguous. It matches **each**
     pickup anchor against that union (`StoreNameMatch`) and extracts that anchor's running key from
     its matched form — the shadow resolver's all-lines behavior, so **every** store in a
     multi-store stack is keyed, not only the drop the receipt rode on. A store's key is the first
     non-null match for that anchor, so a same-store sibling whose own line didn't match still
     inherits the store's key. Anchors on pickup rows; a job with delivery rows but no pickup rows
     produces no store link (v1, matches today's shadow behavior).
     - *Accepted residual (non-fielded):* a platform that emits **mid-stack / changing** receipts
       (two completions carrying different `parsedPay`) would put a non-null `payoutStoreForms` on
       >1 row and blend the union — the same accepted-residual class as the documented #528
       mid-stack money under-attribution. If such a platform lands, define the union's row ordering
       and prefer the keyed form; not a v1 gap under the one-receipt-per-job shape.
   - Upsert one `stores` row per resolved link — deterministic `storeKey` (D2), first-observed
     name forms kept, `lastSeenAt` advanced from `obs.timestamp`. The key is monotonic by
     construction now (row-sourced), so the running key never regresses.
   - **`UPDATE storeKey` onto the job's `pickup_records` + `delivery_records` by
     `jobId`/`taskId`, `WHERE storeKeyPinned = 0`** — an upgrade re-stamp (chain-only→keyed)
     overwrites the prior key, but a driver-pinned row (H1) is skipped and keeps its
     `normalizedChain` grouping.
   - Stamp `offer_records.storeKey` + `linkedJobId` on the matched offer (offer link below, F4).
   - **No orphan chain-only rows (M4):** when a re-stamp upgrades a job's rows from
     `platform|chain|` to `platform|chain|key`, the chain-only `stores` row is left referenced by
     zero visits. The store list is therefore read as `SELECT DISTINCT storeKey` over the visit
     rows joined to `stores` metadata (an unreferenced chain-only key simply doesn't appear),
     rather than by listing every `stores` row — so an upgraded-away provisional key shows no
     phantom entry. A genuinely payout-less-only chain keeps its chain-only row (still referenced).
4. **Idempotency / refold equivalence (corrected, F11).** A re-drain over an already-committed
   suffix **cannot happen** — the watermark advances atomically with the records in one
   transaction (idempotency does not rest on the record PKs alone). What must hold is
   **incremental fold ≡ from-zero refold**: both process events in ascending `sequenceId`, commit
   the same rows (including the row-persisted `payoutStoreForms`), and run the same
   `StoreResolution` tasks in the same order against the same committed row set. Because resolution
   reads committed rows only (never a single-event payout, never in-memory accumulation) and is a
   pure function of them, and the running key is monotonic, the two paths converge to byte-identical
   `stores`/`pickup_records`/`storeKey` state. Re-runs are genuinely stable no-ops.

### Offer↔job association (D3, resolve-from-rows form)

The offer link is a **secondary enrichment** — pickup anchors carry the core resolution; the
offer contributes `offerNameForm` on the `stores` row and the `offer_records.storeKey` stamp.
So the design degrades gracefully to the temporal fallback with no payload change, and the
enrichment only sharpens future data.

- **Exact (future events):** add `jobOfferHashes: List<String> = emptyList()` to
  `PickupPayload` and `DeliveryPayload`, populated at the emit sites from the job's
  `parentOfferHashes` (feasibility confirmed — `Job.parentOfferHashes` exists and the job is in
  scope at every completion/pickup emit; a site that can't reach it degrades to `emptyList()` →
  temporal, F12). Nullable-with-default ⇒ old events deserialize identically ⇒ **no forced
  projector bump from the payload change itself** (the v5 bump ships anyway for the new tables).
  At resolution, the linked offer(s) are `offer_records WHERE offerHash IN (jobOfferHashes)`;
  the offer form is that row's existing `merchantName`.
- **Fallback (historical / empty `jobOfferHashes`):** within the session, **nominate** the most
  recent `OFFER_ACCEPTED` `offer_record` at-or-before the job's first pickup/delivery whose
  `linkedJobId IS NULL OR = thisJob`. **Nomination is not a link (F4):** stamp
  `offer_records.storeKey`/`linkedJobId` only if the offer's own `merchantName` **agrees by
  brand token** (`StoreNameMatch`) with a resolved pickup anchor — otherwise the offer is a
  queued *next-job* offer (DoorDash presents the next offer mid-job) and linking it would
  poison per-store offer analytics. The persistent `offer_records.linkedJobId` is the
  claimed-offer set: it survives batch boundaries (fixing the accumulator's ephemerality) and a
  from-zero refold re-derives the same claims in the same event order — refold-deterministic.
  **Never used for money** — the delivery `storeKey` comes from the pickup anchor, and
  `netProfit`/cpm are untouched.

### runningKey extraction hardening (D4, from #554's polish list)

Extend `StoreChainProjector.extractRunningKey` + payout matching, all in `:domain` with unit
tests per shape:

- `#NNN` franchise suffixes (`SPROUTS FARMERS MARKET #161`).
- Place-name parentheticals (`CAVA (Sonterra Village)`, `(Stone Oak)`, `(San Antonio)`).
- Hyphenated store codes (`Little Caesars (0164-0045)`).
- `realizedTip` sums **all** payout items matched to a store (today: single best match).
- Payout↔store matching may use the address / running key when brand tokens share zero
  overlap (grocery payout lines that are bare order numbers).

### Key determinism (F7)

The `storeKey` must be a pure, stable function of the resolved surfaces or refold ≠ incremental:

- **`normalizedChain = normalizer(pickupCanonicalAnchor)`, always** — never derived from the
  payout or offer form. The pickup anchor ("Maple Street Biscuit Company") and payout form
  ("Maple Street Biscuit - Alamo Ranch") normalize differently; pinning the source removes the
  ambiguity. The normalizer is the one shared `:domain` function used everywhere `normalizedChain`
  appears.
- **`runningKey` is case/whitespace-normalized** (`Locale.ROOT` lower, collapse whitespace) so
  `Alamo Ranch` and `alamo ranch` don't fork one store into two keys.
- **Extraction precedence stays deterministic:** when a payout form carries both a parenthetical
  code and a ` - Area` suffix, the parenthetical wins (today's `StoreChainProjector` behavior) —
  keep and test it.
- **Accepted residual (no alias table, D2):** a store whose payout form flips between
  `(02426)`-style and ` - Area`-style across sessions forks into two entities. Named, not fixed —
  a phase-2 alias table (or the OTA lookup) is the home for cross-form unification.

### Store-name correction interplay (F2 + H1)

`DELIVERY_ADJUSTMENT` with a `newStoreName` (`applyDeliveryAdjustment`) must **null the row's
`storeKey` AND set `storeKeyPinned = 1`** (it preserves `storeKey` today via `row.copy`, so the
stale key would win the report-card grouping and the driver's fix would be invisible — the exact
#526 mislabel case this feature exists to fix). The pin is load-bearing (H1): resolution keys off
the **pickup anchor**, and a driver correcting a mis-attributed drop is precisely the case where
the corrected name *disagrees* with the anchor — so without the pin, the next re-resolution would
re-stamp the row back to the anchor store and silently undo the correction (and do it
batch-dependently, breaking rebuild ≡ backfill: the overwrite happens only when the close trigger
and the correction share a batch). With the pin, the corrected row is driver-owned forever: its
grouping is read-side `normalizedChain(newStoreName)` (F9), and every resolution `UPDATE` carries
`WHERE storeKeyPinned = 0`. Store resolution runs **after** adjustments in the transaction so a
same-batch correction is already pinned when resolution runs; both are one `sequenceId`-ordered
apply stream (the interleaving-determinism the projector's FIX 1 established for PA/DA).

## Reads (the #315 store report card)

New `AnalyticsDao` queries, session-anchored where period-scoped (#655), all derive-at-read:

- **Store list / report card:** per `storeKey` (or rolled up per `normalizedChain`):
  pickup count, avg + p50/p95 dwell (`confirmedAt − arrivedAt` over `pickup_records`,
  optionally segmented by `activity`), delivery count, realized gross/net
  (`delivery_records` grouped by `storeKey`, falling back for unresolved rows so
  pre-resolution history still shows up), first/last seen.
- The existing `deliveryTotalsByStore` query migrates from `GROUP BY storeName` to grouping on
  the resolved key — the fragmentation fix this issue exists for. **F9:** an unresolved row
  (`storeKey IS NULL` — a `MANUAL` delivery that was never in a job, or a not-yet-resolved row)
  must **not** group under its raw `storeName` alongside the `platform|target|02426` entity, or
  it fragments the report card. Group unresolved rows by the shared `:domain`
  `normalizedChain(storeName)` at read (a computed grouping column), not by raw text — so
  `"Target"` and `"target|02426"` at least share a chain bucket.
- **Chain-only ("location unknown") rows, F6:** a `platform|heb|` row (no running key ever
  observed — every visit was payout-less) is a **bucket, not a location**: two different H-E-Bs
  visited on payout-less jobs both stamp `platform|heb|`, so its dwell population blends and its
  per-location p50/p95 are meaningless (and history can't re-attribute — the per-job data has no
  key even on a refold). The #315 report card must render such a row as "location unknown for
  this chain", and the coalesce-at-read rule states per-location stats are partial by construction.
- Percentiles computed in the repository from the fetched dwell list (Room/SQLite has no
  native percentile; store visit counts are small) — keeps SQL simple and honest.
- **Per-store OFFER stats are phase-2 for declined/timeout offers.** An offer that was declined
  or timed out never becomes a job, so `offer_records.storeKey`/`linkedJobId` stay null for it
  forever (resolution is job-close-triggered). The Decisions-tab "accept rate / value-of-saying-no
  by store" therefore needs a read-side `normalizedChain(merchantName)` join on `offer_records`,
  not the stamped key — flagged now so #315 doesn't rediscover it.

## Principles walk

- **UDF/purity:** resolver + normalizer are pure `:domain` (`obs.timestamp` only); resolution
  reads committed rows and writes both stay at the projector transaction edge (no cross-event
  in-memory accumulation — F1).
- **SSOT:** one normalizer feeds `normalizedChain` everywhere; stats derived, never stored.
  **M5 — the resolver is a rebuild, not a rename.** Today `StoreChainProjector.project(job: Job,
  payout)` reads `job.tasks`/`job.offerStoreHint`/`task.customerNameHash`; resolve-from-rows has
  no `Job` — it takes `pickup_records`/`delivery_records` row lists + the row-sourced running key.
  So extract one pure `:domain` core over neutral row/surface inputs, with two thin adapters:
  the row adapter (the projector) and the `Job` adapter (`ShadowStoreChainLogger`, kept compiling
  and running as the field-verification mirror). That is still one resolution SSOT — retire the
  shadow logger only when the persisted path has its two field confirmations.
- **Security/privacy:** store names/addresses are merchant data — fine at rest, **never in
  INFO+ logs** (P7; the report card is UI, not logs). Customer data stays hashed
  (`customerHashes` on the chain are sha256 prefixes already). No network. No new capture
  surface.
- **P8 platform-agnostic:** no `doordash_*` naming anywhere; `platform` column from the
  session's `Platform.wire`; extraction patterns are surface-shape heuristics (documented as
  such), and any platform that needs different shapes extends the *data* (phase-2 OTA lookup
  / ruleset-adjacent), not a Kotlin `when` on platform.
- **Additive-only migrations, fail-loud posture:** unchanged; v12 is additive; no
  destructive fallback.

## Test plan

- `:domain` unit: normalizer edge cases (the issue's chain-derivation list + the hardening
  shapes + F7 case/whitespace); resolver link/attribution cases (existing
  `StoreChainProjectorTest` extended); temporal-fallback with the F4 brand-token guard
  (a queued next-job offer must NOT link to the current job).
- Fold tests (`:core:data` level, same harness as existing projector tests): fresh fold produces
  stores/pickup_records/storeKey stamps; **incremental fold ≡ refold-from-zero with a batch
  boundary that splits a job's pickups from its close** (the F1 regression guard — the exact case
  the accumulator design failed).
- **B1 regression guards (the second-vet blocker):** `PICKUP_CONFIRMED(keyed payout drop) →
  DASH_STOP` asserts the storeKey stays keyed after the payout-less close (no downgrade to
  chain-only); `keyed drop → later payout-less add-on drop` asserts no downgrade; re-run
  resolution over an unchanged row set is a byte-identical no-op (true only because the key is
  row-sourced).
- **Multi-store stack guard (the third-vet HIGH — the Target+Maple case #159 exists for):** a
  stacked job where drop1 (`Target`) carries the full receipt (`parsedPay` = both `Target (02426)`
  and `Maple Street Biscuit - Alamo Ranch`) and drop2 (`Maple`) carries `parsedPay = null`, closed
  by a payout-less `DASH_STOP`, resolves **both** stores keyed (`…|target|02426` **and**
  `…|maple|alamo ranch`) — not Maple chain-only. Assert identical on incremental vs refold, and
  assert it **matches the field-verified shadow log** (the `StoreChainProjector` parity the test
  plan already names on the 06-19 captures — the per-drop extraction would have failed it).
- **H1 regression guard:** a `DELIVERY_ADJUSTMENT` newStoreName that **disagrees with the pickup
  anchor** pins the row, and a subsequent resolution trigger in the SAME batch does NOT re-key it;
  and the same log yields identical rows on incremental fold and from-zero refold (the
  rebuild-faithfulness case). Plus: `PROJECTOR_VERSION` wipe clears `stores`/`pickup_records` (F8).
- DAO tests: report-card aggregation, unresolved-row `normalizedChain` grouping (F9), chain-only
  "location unknown" row (F6), dwell math.
- Migration: `MigrationTestHelper` v11→v12 case; `SchemaVersionGuardTest` chain.
- `SessionReplay` (Level B) on a real captured stacked session (the Target+Maple 06-19
  captures if in corpus): assert the resolved chain matches the field-verified shadow log.

## Vet findings resolution

**First pass (F1–F12)** drove the resolve-from-rows redesign. **Second pass (B1/H1/M1–M5/L1–L2)**
caught that the first revision only *half*-committed to rows — it read anchors/dropoffs from rows
but the running key from the trigger event, so a payout-less `DASH_STOP` downgraded every keyed
store (B1) and re-resolution silently undid driver corrections (H1). **Third pass (B2)** caught
that the B1 fix, by extracting only *this drop's* payout line, discarded sibling-store keys in a
multi-store stack (one receipt rides one completion) — the headline Target+Maple case. This
revision persists the **full receipt** store-form set onto the receipt-bearing `delivery_records`
row and matches every anchor against it (row-sourced, monotonic, all-stores), and pins driver
corrections — closing all three.

| # | Sev | Resolution in this spec |
|---|---|---|
| **B1** | **BLOCKER** | Receipt evidence **persisted to `delivery_records.payoutStoreForms` at fold time** from `parsedPay`; resolution reads it from rows, never from a trigger event → a payout-less `DASH_STOP`/add-on re-run computes the SAME keyed `storeKey`, no downgrade. |
| **B2** | **HIGH** | Persist the **full** receipt store-form set (not just this drop's line) on the one receipt-bearing row; resolution matches **each** anchor against the union → both stores of a stack are keyed (shadow-log parity), fixing the Target+Maple under-resolution the per-drop fix introduced. Store-granularity key aggregation stated explicitly. |
| **H1** | **HIGH** | Driver `newStoreName` sets a **dedicated** `storeKeyPinned = 1` (not an `originalStoreName`/`USER_CORRECTED` overload); every resolution `UPDATE` carries `WHERE storeKeyPinned = 0`, so a correction disagreeing with the pickup anchor is never re-keyed back (batch-order-independent → rebuild-faithful; pin refold-derived from the event). |
| M1 | MED | `resolutions` applied `sortedBy { sequenceId }` in the transaction. |
| M2 | MED | Transaction order fixed: deliveries → pickups → sessions → adjustments → resolutions → watermark (pickups + adjustments strictly before resolutions). |
| M3 | MED | `offer_records.linkedJobId` (+ the B1/H1 columns) now declared in the New-columns schema section. |
| M4 | MED | No orphan chain-only `stores` rows — the store list reads `DISTINCT storeKey` over referenced visits, so an upgraded-away provisional key shows no phantom entry. |
| M5 | MED | Resolver called out as a **rebuild** around row lists (one pure core, row adapter + `Job` adapter for the shadow logger), not a rename of the `Job` signature. |
| L1 | LOW | "Re-run is a stable no-op" is now *true* (row-sourced key) and the test asserts it as a guard, not a buggy passthrough. |
| L2 | LOW | Session-level triggers enumerate jobs via `SELECT DISTINCT jobId WHERE sessionId = ?`. |
| F1 | BLOCKER | Fold redesigned **resolve-from-rows** — no per-job accumulator; resolution reads the job's committed rows inside the transaction (the `applyDeliveryAdjustment` precedent). |
| F2 | HIGH | Folded into H1 (pin + null on `newStoreName`; resolution after adjustments). |
| F3 | HIGH | Close triggers enumerated (every completion + `DASH_STOP` + inferred-close), resolution re-runnable and now genuinely convergent (B1). |
| F4 | MED | Temporal fallback only *nominates*; stamps `storeKey`/`linkedJobId` only on brand-token agreement; persistent `linkedJobId` = refold-deterministic claimed-set. |
| F5 | MED | `platform` is the first `storeKey` segment — no cross-platform chain collision. |
| F6 | MED | Chain-only row = "location unknown"; per-location stats partial by construction (+ M4 removes the orphan-row artifact). |
| F7 | MED | `normalizedChain` = normalizer(pickup anchor) always; `runningKey` case/whitespace-normalized; parenthetical-wins precedence; format-flip fork an accepted residual. (The broken "guard the column" phrasing is gone — the key is now row-sourced.) |
| F8 | MED | `PROJECTOR_VERSION` wipe extended to `stores`/`pickup_records`. |
| F9 | LOW | Unresolved / pinned rows grouped by `normalizedChain(storeName)`, not raw text. |
| F10 | LOW | Arrived-then-cancelled pickup = no visit row — named v1 gap. |
| F11 | LOW | Idempotency claim corrected (invariant is incremental ≡ refold, not PK-only). |
| F12 | LOW | `jobOfferHashes` plumbing confirmed feasible; degrades to `emptyList()` → temporal. |
| scope | — | Per-store OFFER stats for declined/timeout offers flagged as a read-side `normalizedChain` join for #315. |

## Phase 2 (explicitly deferred)

- Evaluator pre-enrichment (p50 dwell into `OfferEvaluation`) — touches offer-engine
  freezing semantics; own design pass.
- OTA chain-normalization lookup (`"chipotle mexican grill"` → `"Chipotle"`) — data-shaped,
  belongs with the matchers/OTA infrastructure (#192-family).
- Per-order offer↔store bridge for multi-store offers on `offer_records`.
- Session-uniform-cpm replacement via the now-logged `jobOfferHashes` (frozen-economics
  decision, not a stores decision).
