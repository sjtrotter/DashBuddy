## 2026-06-13 — DoorDash session (live evening dash, post-#487 build)

- **Platform tested:** DoorDash
- **Branch under test:** `master` @ `55b93d0` (post the morning batch + #491 field-log). Includes the
  #460/#324 **co-hero task-card redesign** (`39a54a9`) and the #461/#476 Shop & Deliver cards.
- **Field conditions:** live dash, narrated in real time while driving. First HEB offer of the day;
  later declined one offer and took a second (also HEB). Recorded for triage — **hypotheses, not
  concluded fixes.**

### Bugs

#### 1. Premature/duplicate **drop-off card** — recognized before the screen fully loaded (same class as the ghost offer)
On the first delivery (first HEB of the day), the **drop-off card appeared before the screen finished
loading** — it briefly showed "Drop off customer", a **~2-second** timer, and **`$37/hr`** — and then
**a second drop-off card appeared directly after it**. The dasher explicitly tied it to the ghost
offer ("did the same thing as the offer where it recognized the screen before it fully loaded").

- **Context on the new look:** the `$37/hr` is expected — the #460 co-hero redesign now shows a live
  **"Running at $/hr"** on task cards (`FlowCardItem.kt` co-hero, ~`:514-560`). So the `$/hr` itself
  isn't the bug; the **two drop-off cards** are.
- **Likely cause (hypothesis):** same **partial-frame** class as the 2026-06-13 desk-review ghost
  offer (#1, prior entry) — a dropoff-nav/arrival frame recognized **mid-render** opens a Delivery
  card before the real one settles, leaving two. In `FlowCardMapper`, `DELIVERY_NAV_STARTED` opens a
  card keyed by `taskId`; a premature frame with a missing/again-different `taskId` (or an
  arrived-then-replaced frame) would produce a second card. This is **distinct from #470** (which
  fixed the frozen-`completed` + live-`active` overlap of the *same* taskId) — here the symptom is a
  *premature* card from an unsettled frame, not the at-door overlap.
- **To confirm (desk):** pull this delivery's `captures/` + `app_events` — expect a dropoff frame
  recognized very early (short dwell, ~2s) and **two Delivery rows / card ids** for the one stop
  (check whether the taskIds match or one is empty/garbage). If the early frame parsed empty (no
  customer/store), it's the same empty-partial-render root as the ghost offer → argues for a shared
  **settle/validity gate** on recognition (offer **and** task screens), a desk call.
- **Live update — did NOT recur:** on the **second** delivery (the next order's dropoff) **only one
  drop-off card showed**. So this may have been a **stray/transient** one-off rather than a
  reproducible dup. Downgraded to low-confidence; keep an eye out, but the capture above is only worth
  chasing if it happens again.

#### 2. Co-hero "Running at $/hr" goes **nil on the DROP-OFF** (pickup eventually shows it; shop X/total is fine)
Refined live (the dasher clarified — "running total" = the co-hero **"Running at $/hr"**, not the shop count):

- ✅ **Shop `X/total` works** — confirmed on **both** HEB pickups this dash. So the redesign's shop
  progress is fine; **rule out** the earlier candidate (b).
- ⏳ **Pickup `$/hr` populated (eventually).** The second HEB pickup card **eventually showed `$51/hr`**,
  which is **in line with the offer** — so the value is correct, but it may have been **slow to
  appear** ("eventually"), i.e. the blended economics threaded in a beat late.
- ❌ **Drop-off `$/hr` is nil.** On the drop-off the co-hero reads **~nil** ("Running at —").

- **Why this is odd (grounded):** `LiveCardBuilder` feeds **both** the live Pickup and the live
  Delivery the **same** source — `region.activeJob?.blendedNetPay` / `blendedEstMinutes`
  (`LiveCardBuilder.kt:77-78` pickup, `:93-94` delivery) — and `projectedHourly` returns null **only**
  when `netPay`/`estMinutes` is null **or `estMinutes <= 0`** (`FlowCardItem.kt:669-673`). Since the
  pickup showed `$51/hr`, `activeJob.blended*` was populated during pickup — so a **nil drop-off**
  means those values **don't survive into the dropoff phase** (or `blendedEstMinutes` has eroded to
  `0`/null by then).
- **Hypothesis:** the blended offer economics are dropped/zeroed at the **PICKUP→DROPOFF** transition
  (e.g. `blendedEstMinutes` is "remaining estimate" and goes to 0 once picked up, or `activeJob` is
  re-derived for the dropoff leg without re-blending). The "eventually" on pickup also hints the
  blend populates **late**.
- **To confirm (desk):** inspect `region.activeJob.blendedNetPay` / `blendedEstMinutes` (and how
  they're computed) at **dropoff** vs **pickup** for this job — expect one of them null-or-0 at
  dropoff. Decide whether the dropoff co-hero should reuse the **accepted-offer** net/time (fixed,
  like `FlowCardMapper`'s `acceptedNetPay`/`acceptedEstMin`, `FlowCardMapper.kt:161-162/186-213`)
  rather than a "remaining" blend that decays to 0. (Desk call — not a concluded fix.)

#### 3. **CONFIRMED** — the "tip added" bubble message bypasses the money-formatter SSOT (raw float)
The post-delivery **additional-tip** notification that posts to the bubble (e.g. *"Nice! $2.0 tip from
Cheesecake Factory"*) shows a **raw float** — it has a `$` but isn't currency-formatted (`2.0`, not
`2.00`). Same class as the "Saved: $X" fix (#456/#466), but a **separate message that was missed** in
the "route everything through the domain `Formats` SSOT" sweep.

- **Confirmed in code (not a hypothesis):** `TipEffectHandler.kt:23` builds the bubble text as
  `"Nice! \$${effect.amount} tip from ${effect.storeName}"` — `effect.amount` is a `Double`
  interpolated raw. The log line `:21` (`"Tip received: \$${effect.amount} …"`) has the same raw
  interpolation.
- **It's the only straggler:** a sweep of `app`/`core`/`domain` for `$`-prefixed raw money
  interpolations (excluding tests) found **only** `TipEffectHandler` (`:21`, `:23`). The tip line in
  the card breakdown already uses the SSOT (`FlowCardItem.kt:704`, `Formats.money(tip.amount)`).
- **Fix direction (SSOT, the obvious route — desk/follow-up):** route the amount through the
  `:domain` money formatter — `Formats.money(effect.amount)` → `$2.00` — in both the bubble text and
  the log line, exactly as `FlowCardItem`/the "Saved" message now do (#456/#467). A one-line change;
  recorded here per "note + follow-up."
