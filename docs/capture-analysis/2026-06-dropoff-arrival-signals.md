# Drop-off Arrival Signals — June 2026 (per delivery type)

**Analysis date:** 2026-06-08  ·  **Scope:** every June field session under
`/home/betty/dashbuddy/logs/2026/06` (days 03, 04, 05, 06, 07), DoorDash, all
three capture sources + each day's event DB + app logs.  ·  **Method:** 5-agent
fan-out (one per day) extracting a per-delivery record (type, flow-transition
sequence, nav→arrived edges, ARRIVED/CONFIRMED/COMPLETED counts, and the
concrete UI node that distinguishes real arrival from preview), then a synthesis
pass grounded in the live rules + state machine. **23 deliveries** analyzed.

> **Status:** Analysis only — every proposed change below is a **field-test
> hypothesis**, not an applied fix. No code changed. The point is a reliable
> arrival signal **per delivery type**, since DoorDash's drop-off UX differs by
> type and a single "the drop-off workflow is showing" trigger is wrong for some.

---

## TL;DR

Drop-off arrival splits cleanly **by delivery type**, and the reliability of our
current detection splits with it:

| Branch | n (June) | Current behavior | Reliable arrival signal | Status |
|---|---|---|---|---|
| **leave-at-door** | 14 | ✅ clean (single fire) | **photo screen** — `id=steps_screen_content` + "photo of drop-off" (arrival-only) | works |
| **hand-to-customer** | 6 | ❌ **6/6 premature + duplicated** | **"Mark as delivered" CTA** — `id=textView_prism_button_title` (absent on preview card) | **broken** |
| **PIN / meet-at-door** | 1 | ✅ clean | PIN-collect screen — `dropoff_pin_entry` text | low-n |
| **alcohol / 21-ID** | 1 | ✅ clean (fragile) | "ID barcode scan" + "Start scan" — `dropoff_alcohol_id_scan` | low-n |

**The one systemic defect is hand-to-customer.** The `dropoff_handoff` rule maps
the bare "hand it to customer" card straight to `task:dropoff:arrived` on the
*instruction text*, but DoorDash renders that exact card at **nav-start** (the
instant you confirm pickup), so `DELIVERY_ARRIVED`/`CONFIRMED` fire **6–8 minutes
early**, and the card then flaps `arrived ↔ navigation` and re-fires `CONFIRMED`
2–3×. The single byte-level difference between the premature preview card and the
real-arrival card — in **all six** handoffs — is the completion CTA
**"Mark as delivered"** (`id=textView_prism_button_title`).

**leave-at-door is the proof it can be clean:** its arrival screen
(`dropoff_photo`, `steps_screen_content`) only ever renders at the geofence, never
as a preview — so it fired exactly once in 14/14 deliveries, zero premature, zero
flap.

---

## Per-delivery-type branches

### 1. Leave-at-door — RELIABLE (14/14 clean)

- **Arrival signal (primary):** the photo completion sub-screen —
  `dropoff_photo` (priority 62), keying `id=steps_screen_content` +
  `hasTextContaining "photo of drop-off"` (`step_title="Take photo of drop-off
  location"`). This container **never appears in any pre-arrival/navigation
  frame**, so the nav→arrived edge is the true arrival.
- **Evidence:** 14/14 single-fire across 06-03 (1), 06-04 (1), 06-05 (2: Chris M,
  Kody C), 06-06 (6: D1, D2, D3 both legs, D4, D7), 06-07 (4: 09:28, 10:17,
  12:21, 16:25). Zero premature, zero flap.
- **Red herrings — do NOT gate arrival on these (all observed pre-arrival):**
  - **Instruction text "Leave it at the door"** — present in the pre-arrival
    *preview* card and the photo frame alike (06-06 D2, 06-04). Non-discriminator.
  - **Pre-arrival CTA "Continue"/"Directions"** (`id=textView_prism_button_title`)
    — this is the geofence-proximity/preview button; "Continue" rendered **5 min
    before** real arrival on 06-03. Not arrival.
  - **`dropoff_geofence_warning` ("far away from the customer", priority 84)** —
    fired **4 s *before*** the genuine photo screen at a true arrival (GPS jitter,
    06-03 job1 17:59:19; 06-06 D1 11:48:30). It is a **red herring both ways**:
    never a positive arrival signal, and (because it can fire at a real arrival)
    not a usable premature-arrival suppressor either.
  - CTA *string* varies ("Take photo" → "Complete delivery", but Chris M 06-05
    read "Handed order to customer") — key on the `steps_screen_content`
    **container**, not the button text.

### 2. Hand-to-customer — BROKEN (6/6 premature + duplicated)

- **Arrival signal (primary, recommended):** the completion CTA
  **`id=textView_prism_button_title` text="Mark as delivered"** under
  `drop_off_workflow_host_fragment` (and/or `id=complete_delivery_steps_button`).
  This is the **only** node that reliably separates the nav-start preview card
  from the real-arrival card.
- **Current behavior (the bug):** `dropoff_handoff` (priority 64) requires only
  `drop_off_workflow_host_fragment` + `hasTextContaining "hand it to customer"`
  and maps that to `task:dropoff:arrived`. DoorDash shows that exact card at the
  same millisecond as `PICKUP_CONFIRMED`/`DELIVERY_NAV_STARTED`, so arrival fires
  at nav-start — **6–8 min and several miles early.**
- **The flap & duplication:** after the premature arrived frame, the card
  momentarily relayouts to an empty/nav card and back, re-entering
  `TaskDropoffArrived`. `DELIVERY_CONFIRMED` re-fires on each re-entry.
  - 06-07 `6f3a4a45`: `handoff(arrived) → pre_arrival(empty) → handoff(arrived)`
    **within one second** → ARRIVED ×2, CONFIRMED ×3.
  - 06-03 `effd635b`: **ordering inversion** — CONFIRMED fired at 21:44:54 (the
    premature flap) *before* ARRIVED at 21:56:44 (the real edge).
- **Evidence (6/6 leaky, discriminator held 6/6):** 06-03 effd635b (America S,
  CONFIRMED ×3), 06-05 Clint H + Robin B (premature + flap; Robin B COMPLETED ×2),
  06-06 D8 Diane B, 06-07 6f3a4a45 (×2/×3) + 879f03b7 + 365eb1dc. In every case
  the nav-start card had only "Directions"; the real-arrival card added
  **"Mark as delivered."**
- **Red herrings — do NOT gate on these:**
  - **Instruction text "hand it to customer"** — identical at nav-start and
    arrival. This is the root-cause false signal.
  - **Call/Message + Apt/Suite line** — populate at real arrival in *some*
    handoffs (Robin B, 879f03b7, 365eb1dc) but were **present in the premature
    frame** of 6f3a4a45. Not reliable across deliveries.
  - **No geofence signal exists for handoff** — grep returned no "far away" text
    in any handoff frame on any day. Arrival is signalled **only** by the CTA.

### 3. PIN / meet-at-door — RELIABLE per-frame, but n=1 (LOW confidence)

- **Arrival signal:** `dropoff_pin_entry` (priority 63),
  `drop_off_workflow_host_fragment` + ("collect pin from customer" | "ask the
  customer for the unique"); `step_title="Collect PIN from customer"`. Like the
  photo screen, this text is **arrival-only** — no nav-start preview flap.
- **Evidence:** 1/1 clean — 06-05 Stop A of the stacked batch `b9f297a2` (Steven
  W @ Irish Pub, 19:36:52). Only one PIN delivery in all of June → below the
  two-confirmation bar.
- **Adjacent gap:** the actual PIN-pad ("Enter PIN" / "Submit") is captured as
  UNKNOWN (06-05 19:36:54–19:37:11). Post-arrival; doesn't affect the arrival
  edge, but unmatched.

### 4. Alcohol / 21-ID verify — RELIABLE but FRAGILE, n=1 (LOW confidence)

- **Arrival signal:** `dropoff_alcohol_id_scan` (priority 67) — "ID barcode scan"
  + "Start scan". The prior `dropoff_alcohol_id_intro` (priority 68) is correctly
  `task:dropoff:navigation`, so the scan screen is the arrival edge (the #149
  decision: fire on the scan screen, not the intro).
- **Evidence:** 1/1 clean — 06-06 D6 Yessenia C (ID scan 18:34:43; ARRIVED→CONFIRMED
  gap ~5.5 min, expected for ID verification).
- **Fragility flagged:** the alcohol delivery's nav-start "Hand it to recipient"
  card (06-06 18:15:17, UNKNOWN — `instructions_title="Hand it to recipient"`,
  no completion CTA) is the **same shape** as the handoff premature card. It did
  **not** misfire **only because** a concurrent map frame
  (`mapViewLayout`/`speedInfoView`) won the priority race and matched
  `dropoff_navigation`. That's luck, not design — the same CTA-gating fix
  proposed for handoff would make it robust.

---

## Cross-cutting structural findings

1. **`DELIVERY_CONFIRMED` has no idempotency guard.** `EffectMap.kt:428-436`
   emits it on **any** transition where `prevTask.phase==DROPOFF && (nextTask==null
   || nextTask.taskId != prevTask.taskId)`. The flap and any task re-mint satisfy
   this → 2–3× CONFIRMED. There is no "confirmed-once-per-leg" latch.

2. **`DELIVERY_ARRIVED` is latched on `arrivedAt` — but a re-mint defeats it.**
   `EffectMap.kt:461` fires only when `prevTask.arrivedAt==null`, and
   `PlatformRegionStepper.kt:530` preserves `arrivedAt` across a *same-task* flap.
   So a re-fire of ARRIVED **requires a new `Task` to be minted** (resetting
   `arrivedAt` to null). The re-mint happens during the flap via either
   `isStackedDropoffTransition` (`:474`, keyed on `customerAddressHash` change) or
   a phase/identity mismatch through an empty/relayout frame. **The exact trigger
   per delivery can't be pinned from the capture DB** (taskId/phase are internal
   state, not in `observations`) — but the consequence (re-mint → re-fire) is
   firm, so the fix should be trigger-independent.

3. **Arrived sub-screens parse to `_type:None`.** `dropoff_photo`, `dropoff_handoff`,
   `dropoff_pin_entry`, `dropoff_alcohol_id_scan`, `dropoff_completed_confirm` carry
   `state.flow` but **no `parse` block** → `ParsedFields.None` (no
   `customerAddressHash`/`storeName`). So on the real-arrival handoff frame
   `taskFields` is null and the stepper's stacked-dropoff guard must rely on the
   address carried only by the *navigation/pre_arrival* frames. Adding a minimal
   identity parse (at least `customerAddressHash`/`customerNameHash`) to the arrived
   screens would let any latch key on **identity** instead of phase edges.

4. **Stacked batches are LEGITIMATE double-arrivals — not flaps.** 06-05 `b9f297a2`
   (Steven W PIN → Jordan S photo) and 06-06 D3 `8cd4f7d9` (Haley C → sushma s)
   share **one `jobId`** and correctly produce 2× ARRIVED/CONFIRMED — one per
   customer. **Any anti-duplicate latch must be per-leg (`customerAddressHash`),
   never per-`jobId`**, or it would wrongly suppress the second real arrival. The
   existing `isStackedDropoffTransition` is the right granularity and must be
   preserved.

5. **Monotonic-phase principle.** `arrived → navigation` is a *backward* transition
   that never happens for a real delivery (you don't un-arrive). Treating dropoff
   arrived as a **one-way latch per leg** (ignore later NAVIGATION observations for
   an already-arrived leg unless `customerAddressHash` changes = genuine next stop)
   neutralizes the flap at the stepper level regardless of which screen flickers.

6. **Alternate-completion flows re-fire CONFIRMED from post-arrival screens.**
   When handoff/leave-at-door fall back to "Can't hand order to customer" →
   photo, or hit `dropoff_completed_confirm` ("Confirm order was completed"), those
   screens drive extra CONFIRMED/COMPLETED (06-03 effd635b 22:00:37/41; 06-05 Robin
   B COMPLETED ×2). A per-leg idempotent confirm latch also covers these.

---

## Proposed changes (prioritized field-test hypotheses)

> Ordered by leverage. Each is a hypothesis to confirm on a live dash, not an
> applied fix.

**P1 — Tighten `dropoff_handoff` to require the completion CTA.** Add to the
existing `require.all` an `exists` for `{ hasIdSuffix "textView_prism_button_title"
+ hasTextContaining "Mark as delivered" }` (and/or `hasIdSuffix
"complete_delivery_steps_button"`). The nav-start preview card lacks this node, so
it would fall through to `dropoff_pre_arrival` (→ `task:dropoff:navigation`) and
arrival would fire once, at the real arrival. **Consistent with existing design:**
`dropoff_navigation` (priority 61) *already rejects* on "Mark as delivered"
(`doordash.json:851-854`) — this is the symmetric require. *Field-test:* re-drive a
hand-to-customer; expect ARRIVED/CONFIRMED once, at real arrival.
**Complement:** the [nav-exit arrival model](2026-06-task-arrival-navexit-model.md)
catches this same premature card by an independent tell (it precedes any nav
session). The recommended design **layers** nav-exit (primary) + this CTA-gate
(confirming discriminator + external-nav fallback).

**P2 — Per-leg idempotent `DELIVERY_CONFIRMED` latch.** Track confirmed leg ids
(a `Set` of `customerAddressHash`, falling back to `taskId` for no-address legs)
on `PlatformRegion`; in the `EffectMap` confirm block, drop the emit if the leg is
already confirmed; add on first emit; clear on session end. *Must be per-leg, not
per-`jobId`*, to preserve legitimate stacked doubles (06-05 b9f297a2, 06-06 D3).
*Field-test:* a flapping handoff yields exactly one CONFIRMED; a two-address batch
still yields two.

**P3 — Monotonic dropoff-arrival latch.** Either (a) in `updateTaskLifecycle`,
ignore a NAVIGATION observation that would un-arrive an already-arrived dropoff
**unless** `customerAddressHash` differs (genuine next stop), or (b) gate the
`EffectMap` ARRIVED emit on a per-leg `arrivedLegs` set. *Field-test:* the 06-07
`6f3a4a45` same-second flap yields ARRIVED ×1 instead of ×2.

**P4 — Anti-flap reject on the bare relayout card (handoff/alcohol).** Reject
`dropoff_handoff` when navigation chrome is present (`mapViewLayout` /
`speedInfoView` / `bottom_sheet_in_transit_navigation_container`) or the card has
no completion CTA — neutralizes the empty-card flap windows (06-07 UNKNOWN
11:14:08 / 20:10:08 / 20:10:53). Generalizes the alcohol case's *accidental* safety.

**P5 — New matcher: unreachable-customer completion.** Key on
`id=cant_reach_cx_complete_delivery_button` / `cant_reach_cx_header_title="Leave
order and submit details"` → `task:dropoff:arrived`. *Evidence:* 06-06 D5 (Micah M,
`e83a5a1e`) ran entirely through unmatched `cant_reach_cx_*` screens → **0
DELIVERY_ARRIVED, 2 CONFIRMED**. The photo discriminator doesn't cover it.

**P6 — Minimal identity parse on the arrived sub-screens (enabler for P2/P3).**
Add `customerAddressHash`/`customerNameHash` parse to `dropoff_handoff`/`_photo`/
`_pin_entry` so the latch keys on identity, not phase edges (see structural #3).

**P7 (optional) — General completion anchor.** Recognize `id=complete_delivery` +
`drop_off_tasks_title="Drop-off instructions"` as a type-agnostic
arrival/completion corroborator (where alcohol and alternate-method completions
land). Must not steal priority from the per-type arrival screens on normal flows.

---

## Gaps — not covered by June data (need future captures)

1. **Cash-on-delivery / Red Card hand-off** — dasher isn't taking these yet; no
   data. Distinct branch; the collect/payment surface may collide with
   `SensitiveScreenMatcher` (cf. "payment screens blocked, not recognized").
2. **Pure signature-capture handoff** (non-alcohol signature pad) — not observed;
   June's only signature-like flow was alcohol+21-ID (06-06 D6).
3. **Handoff-to-staff / concierge / front-desk / mailroom / locker** — not
   observed; likely own completion screens distinct from "Mark as delivered."
4. **Plain meet-at-door without PIN** — June's only meet-at-door was PIN-gated.
5. **Hand-to-customer that goes straight to `cant_reach_cx`** (never shows "Mark
   as delivered") — would produce 0 ARRIVED like D5; untested for handoff.
6. **Hard geofence block** (DoorDash *prevents* marking delivered until closer) —
   June only showed the benign dismissable "far away" warning.
7. **Stacked batch with a handoff leg** — all June stacks were leave-at-door/PIN;
   a handoff leg would combine the flap bug with legitimate multi-leg doubles and
   stress the per-leg latch hardest.
8. **Uber drop-off arrival** — June drop-off data is DoorDash-only.
9. **Second field confirmation for PIN and alcohol** — each has exactly one June
   sample, below the two-clean-confirmations bar.

---

## Appendix — per-delivery ledger (23 deliveries)

| Day | Type | A/C/Comp | edges | flags | delivery |
|---|---|---|---|---|---|
| 06-03 | leave-at-door | 1/1/1 | 1 | clean | Dan J |
| 06-03 | hand-to-customer | 1/3/1 | 2 | **dup+premature** | America S (CONFIRMED ×3, ordering inversion) |
| 06-04 | leave-at-door | 1/1/2 | 1 | clean | H-E-B shop |
| 06-05 | leave-at-door | 1/1/1 | 1 | clean | Chris M |
| 06-05 | hand-to-customer | 1/2/1 | 2 | **dup+premature** | Clint H |
| 06-05 | PIN+leave (batch) | 2/2/1 | 2 | dup (legit) | Steven W PIN → Jordan S photo |
| 06-05 | hand-to-customer | 1/2/2 | 2 | **dup+premature** | Robin B (COMPLETED ×2) |
| 06-05 | leave-at-door | 1/1/1 | 1 | clean | Kody C |
| 06-06 | leave-at-door | 1/1/1 | 1 | clean | Desirae R |
| 06-06 | leave-at-door | 1/1/1 | 1 | clean | Soreya S |
| 06-06 | leave-at-door (stacked) | 2/2/1 | 2 | dup (legit) | Haley C → sushma s |
| 06-06 | leave-at-door | 1/1/1 | 1 | clean | Megan K |
| 06-06 | leave-at-door | 0/2/1 | 0 | **gap** | Micah M — `cant_reach_cx` (0 ARRIVED) |
| 06-06 | alcohol/21-ID | 1/1/1 | 1 | clean (fragile) | Yessenia C |
| 06-06 | leave-at-door | 1/1/1 | 1 | clean | Nichole M |
| 06-06 | hand-to-customer | 1/2/1 | 2 | **dup+premature** | Diane B |
| 06-07 | leave-at-door | 1/1/1 | 1 | clean | 09:28 |
| 06-07 | leave-at-door | 1/1/1 | 1 | clean | 10:17 |
| 06-07 | hand-to-customer | 2/3/1 | 2 | **dup+premature** | 6f3a4a45 (same-second flap) |
| 06-07 | leave-at-door | 1/1/1 | 1 | clean | 12:21 |
| 06-07 | hand-to-customer | 1/2/1 | 1 | **dup+premature** | 879f03b7 |
| 06-07 | leave-at-door | 1/1/1 | 1 | clean | 16:25 |
| 06-07 | hand-to-customer | 1/2/1 | 1 | **dup+premature** | 365eb1dc |

**Summary:** leave-at-door 12 clean + 2 legit-stacked doubles + 1 cant-reach gap;
hand-to-customer **6/6 premature+duplicated**; PIN 1 clean; alcohol 1 clean.
