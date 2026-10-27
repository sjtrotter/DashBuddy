## 2026-06-19 — DoorDash session (desk analysis of captured dash data)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `fac5d0d7` (post-#544 SSOT campaign) — **before** the unmerged
  #526 pickup-placeholder work (branch `refactor/526-pickup-placeholders-swap`).
- **Field conditions:** 4 dashes, ~8h. Analysis is a **desk pass over the uploaded data**
  (`~/dashbuddy/logs/2026/06/19/`: db `dashbuddy-v2.db` = 116 `app_events` + 248 `app_state_snapshots`;
  ~110k log lines; 76 UNKNOWN window + 47 UNKNOWN click captures), grounded + adversarially verified.
  **Hypotheses, not concluded fixes.** No code changes from this session.

### Verification TODOs — 06-17 fixes held in the field (confirmation 1/2)

The event stream is **structurally clean** — 18 `OFFER_RECEIVED` (9 ACCEPTED + 9 DECLINED), 9
`DELIVERY_COMPLETED`, every offer carries non-null pay, every completion carries non-null pay + a
distinct customer hash, exactly one completion per job, earnings reconcile to the cent ($152.07
completed vs $145.90 accepted = two legit post-accept tip bumps), and **zero ERROR log lines** across
8h. This is **confirmation 1/2** for each of the following (each still needs a 2nd clean dash):

- **#498** (phantom / "the customer" / $0.00 completion): 0 dropoff tasks with a null `customerNameHash`
  across all 248 snapshots; "the customer" placeholder never stuck; no $0.00 completion.
- **#518** (cross-job leak / double-count): exactly 1 `DELIVERY_COMPLETED` per job for all 9 jobs; the
  #522 idempotency key is firing.
- **#517 / #498 ghost**: all 18 `OFFER_RECEIVED` have a non-null `parsedOffer.payAmount`.

### Bugs / data-quality — the one multi-store stack (job `…210799-1`: Target SHOP + Maple Street PICKUP, $15.15)

This was the **first real multi-store stack on the post-#503 build** and the only rich anomaly of the
day. Two pickups (Target, Maple Street) and two distinct dropoff customers (`8e2dfa`, `e1266f`) formed
correctly — the old heuristic minted both pickups fine and slice-3b kept the drops distinct. But:

#### 1. A PICKUP task acquired a delivery customer hash — *(data-quality, hypothesis → #548)*
The Maple Street **pickup** task carries `customerNameHash=8e2dfa`. It binds on the `pickup_arrival`
frame (`app_events` seq 9; snapshot rowid 45→50). **Likely cause:** the `pickup_arrival` rule
legitimately `sha256`'s the "Order for X" name (`doordash.json:1071`) and the stepper stamps it onto
the active pickup (`PlatformRegionStepper:901`). Privacy is intact (hash at edge); semantically a
pickup shouldn't own a delivery customer, and that hash then bleeds onto the first dropoff. Filed #548.

#### 2. Both dropoffs labelled "Maple Street" — the Target order's drop lost its store — *(bug, hypothesis → folded into #526)*
Final state (snapshot rowid 1005): both DROPOFFs show `store='Maple Street Biscuit Company'`, but the
combined receipt (seq 18 `parsedPay`) proves one is the Target order (`Target (02426) $2.25` alongside
`Maple Street - Alamo Ranch $6.50`), and `offerStoreHint=['Target']`. **Likely cause:** **no dropoff
rule parses a `storeName` at all**, so `storeName = taskFields?.storeName ?: currentTask?.storeName`
(`PlatformRegionStepper:876`) makes each drop inherit the prior pickup's store. There is no
offer-order-index ↔ dropoff binding to recover Target on its drop. **This is exactly the mis-attribution
class #526's swap primitive targets** — and it reshaped #526: the reliable mis-bind signal is *store
divergence* (a dropoff's inherited store ≠ its order's `offerStoreHint`), **not** customer correlation
(which already works), and #526's scope widens to dropoff store re-attribution.

### Field UX context — stacked-drop completion is one combined receipt *(dev clarification, NOT a bug)*

The stack fired 2 `DELIVERY_ARRIVED` + 2 `DELIVERY_CONFIRMED` but only **1** `DELIVERY_COMPLETED`
($15.15 combined). **Dev confirmed in-field (2026-06-19): this is expected** — DoorDash shows **no
PostTask receipt between stacked drops; one combined receipt at the end**. Completion fires on
PostTask-exit (`EffectMap:286`), so one combined completion is correct. The end receipt's `parsedPay`
breaks out per-order pay, which is the data source for **per-drop attribution = #528** (an enhancement,
not a defect).

### Open questions / recognition gaps (lower priority — state stayed clean via grace)

1. **Dropoff-arrived "refined map pin" card → UNKNOWN** (~7 frames; ids `dropping_off_action_view` /
   `complete_delivery_steps_button`). Carries raw name + full address + gate code → any rule MUST
   `sha256` name/address and not store gate-code text. Filed #549.
2. **Shopping sub-flow family → UNKNOWN** (~10 frames): barcode-scan-confirm, substitution / wrong-item
   / shelf-photo / weight-mismatch, and a **"we adjusted your pay" toast** (a mid-shop pay change
   currently invisible to the state machine). Filed #550.
3. **The stack offer's UNKNOWN frames are transient partial renders** of the bottom-sheet (loading →
   Accept-only → Decline-only) — **not** a missed offer; the stable frame parsed the 2-order stack
   correctly. Frame-admission noise, not a recognition gap.

### Meta / architecture — logging is not semantic (→ CLAUDE.md Principle #7, #551)

~110k lines under one Timber tag (`App`), DEBUG 76% / INFO 22% / WARN 1.2% / ERROR 0; INFO is per-frame
`SCREEN:` spam (`dropoff_navigation` ×7,406), WARN is drowned by benign `👻 NULL CHILDREN`. **Privacy
finding:** raw merchant names already leak into INFO+ lines (`INFO/Chat: Pickup: H-E-B`; a TTS line
naming two stores) — a user-shareable bug report from INFO+ would ship third-party identity in
plaintext. Drove the new **Development Principle #7 (Semantic, PII-safe logging)** + implementation
issue #551.
