# Task Arrival via Nav-Exit — viability of a unified pickup+dropoff model (June 2026)

**Analysis date:** 2026-06-08  ·  **Scope:** all June sessions (03–07),
**50 tasks** (25 pickups + 25 dropoffs), DoorDash, captures + event DB + logs.
·  **Method:** 5-agent verification fan-out (one per day) testing a specific
conjecture against every task, then a synthesis grounded in the state machine.
·  **Companion:** [`2026-06-dropoff-arrival-signals.md`](2026-06-dropoff-arrival-signals.md)
(the per-delivery-type signal study this builds on).

> **Status:** Analysis only — no code changed. This evaluates a developer
> conjecture for a *unified, type-agnostic* arrival signal and recommends a
> layered design + an instrument-first first step. All proposals are field-test
> hypotheses.

---

## The conjecture (developer)

1. **ARRIVING** = recognize when the nav screen's top changes to "Arriving at
   \<address\>".
2. **ARRIVED** = when the dasher **exits navigation** (Exit button, or backing
   out to the task card).
3. **One mechanism for any task** — pickup and dropoff are essentially the same
   shape.
4. Guiding principle: *if the DoorDash app believes we've arrived, we should log
   arrived* — we mirror the platform's belief even if GPS was imperfect.

## Verdict

| Claim | Verdict | One-line |
|---|---|---|
| **Nav-exit = ARRIVED** | ✅ **Viable** (the prize) | Session-gated nav→card transition aligns with the platform's own arrival event and **dodges the handoff premature bug 5/5**. |
| **"Arriving at X" signal** | ⚠️ **Partial** | The label is **real** (found 5×) but currently UNKNOWN and captured too sparsely to be load-bearing → optional enrichment. |
| **Exit *button*** | ❌ **Dead** | **0** `exit_button` taps in all of June. Only the *window transition* is realized; drop the button half. |
| **Unify pickup + dropoff** | ✅ **Yes** | Already a shared codepath; this is a refinement, not a new fork. |
| **Mirror platform belief** | ✅ **Correct** | Confirmed by data — DoorDash advanced to the dropoff card even when its own geofence flagged "you seem far away" (06-03). The card *is* the belief. |

**Bottom line: GO, as a *layered refinement* — not a rip-and-replace.** Nav-exit
(session-gated) becomes the primary, premature-immune arrival trigger; the
per-type completion card (the [earlier P1 CTA-gate](2026-06-dropoff-arrival-signals.md))
becomes the confirming discriminator **and** the fallback for external-nav; the
completion screens demote to CONFIRM. Nav-exit and CTA-gate are **complementary,
not competing** — the premature card fails *two independent tells* (it precedes
any nav session **and** lacks "Mark as delivered").

---

## Evidence (50 tasks)

- **Nav-exit alignment:** for every task with a captured in-app nav session, the
  nav→host-card transition landed within ~1 min of the platform's
  `PICKUP_ARRIVED`/`DELIVERY_ARRIVED`. Pickups **+1…+17 s** (mostly +1–3 s);
  hand-to-customer dropoffs **0…+1 s** to the handoff card.
- **Premature-bug immunity (the prize):** on **5/5** premature-handoff instances
  (06-03 America, 06-05 Clint+Robin, 06-06 Diane, 06-07 Karen…) the premature
  `dropoff_handoff` card appeared **at `DELIVERY_NAV_STARTED`, before the nav
  session began** — so it is *not* a nav→card transition, and a session-gated
  nav-exit rule ignores it. (The current screen-class rule mis-fires here, e.g.
  06-06 Diane's spurious `DELIVERY_CONFIRMED` at 19:48:20.)
- **"Arriving at" is real:** `id=arriving_at_subtitle "Arriving at"` +
  `id=arriving_at_title "<destination>"` (e.g. "Wing Daddy's Sauce House"), and a
  `id=bottom_sheet_arrived_header_v2 "Arriving soon"` variant that *replaces* the
  distance/ETA readout. Found in **5** frames (06-05 ×2, 06-06 ×2, 06-07 ×1) —
  **all currently classified UNKNOWN** (no matcher). Plus destination-naming
  maneuvers: `primaryManeuverText "You will arrive at <addr>"` /
  `"<addr> will be on the right/left"` (~5/15 tasks on 06-07).
- **No literal "Arrive" maneuver:** `primaryManeuverText` only ever carries turns
  or road names. "You have arrived" exists **only as a push notification title**,
  not on the nav screen.

---

## The unified model (proposed)

A per-task mini state machine, living in the existing owner of `arrivedAt`
(`PlatformRegionStepper.updateTaskLifecycle`). Emissions in `EffectMap` stay
**byte-identical**; this changes *when* `arrivedAt` is set, not what fires.

```
NAVIGATING ──(opt)──► ARRIVING ──────► ARRIVED ──────► CONFIRMED/COMPLETED
   │                     │                │                    │
 Task*Navigation    "Arriving at"/    nav→host-card        completion
 becomes active     "Arriving soon"   transition AND       screen / CTA
 → stamp            DOM (NEW matcher,  navStartedAt!=null   (demoted to
 navStartedAt       enrichment only)   (real nav session)   CONFIRM)
```

- **Enter NAVIGATING:** when a `Task*Navigation` flow first becomes the active
  task's subPhase → stamp **`Task.navStartedAt`** (NEW field) to *prove a session
  existed*.
- **Enter ARRIVING (optional):** when the nav frame carries the final-approach DOM
  (`bottom_sheet_arrived_header_v2`/`arriving_at_title`/`"will arrive"` maneuver).
  Needs a **new matcher** for the "Arriving soon" variant (today UNKNOWN). Purely
  additive — never required to reach ARRIVED.
- **Enter ARRIVED (set-once `arrivedAt`, exactly as today at `:509/:530`):** when
  the window transitions **from** a `Task*Navigation` flow **to** that task's
  arrival/host card **AND** `navStartedAt != null` (a real preceding session).
  **This guard is the whole fix** — the premature handoff card has no preceding
  nav session, so it no longer sets ARRIVED.
- **CONFIRM/closure:** keep the completion screens (`dropoff_pre_arrival_completion`
  "Mark as delivered", `take_photo`/`submit_photo`, alcohol `id_scan`,
  "Arrived at store") as the **confirming discriminator**, not as arrival.

### Why nav-exit + CTA-gate are complementary (use both)
The premature card is caught by **two independent guards**: nav-exit (it precedes
any nav session) **and** CTA-gate (it lacks the completion affordance). Layer them:
nav-exit is primary; require the destination card to carry its per-subtype arrived
affordance ("Arrived at store" / "Mark as delivered" / photo host / "I've arrived
at recipient") as the confirmation. The combined rule degrades gracefully to pure
CTA-gate when nav was external.

---

## Caveats & risks (what June proves and doesn't)

1. **External-nav dropoffs (real, dropoff-only).** On 06-03 the dasher dropped
   DoorDash nav **4–6 mi out** and finished on Google/Waze/background notification
   nav — so there was **no in-app nav→card transition near arrival**. A *pure*
   nav-exit rule would fire **11.5–24 min early** here. **The per-type card must
   stay as a fallback; nav-exit cannot be the sole signal.**
2. **Leave-at-door is "final approach," not "at door."** Nav-exit →
   `dropoff_pre_arrival` fires at the ~0.3 mi geofence, **up to 6.5 min before**
   the photo-keyed `DELIVERY_ARRIVED` (06-07 J4 = +387 s). For photo subtypes,
   either accept "final-approach" arrival semantics or key ARRIVED on the photo
   host card, not `pre_arrival`.
3. **Exit button is unusable.** **0** taps across 5 days — drop it.
4. **"Arriving soon" is UNKNOWN today.** If a nav-exit detector treats "window
   left `dropoff_navigation`" as nav-exit, *entering* this UNKNOWN variant
   mid-approach could be misread as leaving nav and fire ARRIVED ~3.7 min early.
   **Must recognize `arriving_at_title`/`arrived_header_v2` as STILL-IN-NAV before
   shipping nav-exit.**
5. **Spurious pre-arrival flashes on pickups.** A route-overview card (only
   "Directions", no "Arrived at store") flashes mid-nav (06-07 J1/J8). The rule
   must require the arrived affordance, not the bare `pre_arrival` class.
6. **No-nav / orphan sessions.** 06-06 `fefffdc7` pickup had no captured nav;
   orphan nav-starts open a session that never closes. Needs an orphan/timeout
   cleanup + no-session fallback.
7. **Set-once `arrivedAt` + double nav sessions.** Re-routed pickups and
   PIN-apartment dropoffs (06-05 J3) legitimately produce two arrivals; a fresh
   nav session must be allowed to re-arm (today's stacked-transition mint path —
   verify it still fires under nav-exit).
8. **Capture cadence can't prove the ARRIVING half.** Nav frames persist on
   window-entry, so the near-arrival frame is usually missing. Only **on-dash live
   observation** can establish how often "Arriving soon" actually fires.

---

## Recommendation — instrument first, then flip

**GO, layered.** Smallest first step is a **shadow instrument with no behavior
change** (proves the prize on-device before touching live arrival emission):

1. Add **`Task.navStartedAt`** — stamped when a `Task*Navigation` subPhase is first
   seen in `updateTaskLifecycle`.
2. At the existing `arrivedAt` set-once points (`PlatformRegionStepper:509/530`),
   compute a **shadow** `navExitGated = (navStartedAt != null && navStartedAt <
   obs.timestamp)` and **log it** alongside the existing `PICKUP_/DELIVERY_ARRIVED`
   (e.g. as an event metadata field) — **without changing which event fires.**
3. **Field-test two hypotheses:** (a) on every premature `dropoff_handoff`,
   `navExitGated == false` at the moment the current rule sets ARRIVED, and `true`
   at the real arrival; (b) on a deliberately-external-nav dropoff (last leg on
   Google Maps), `navExitGated == false` at the door — proving the fallback is
   load-bearing.
4. **Two clean field confirmations** (per CLAUDE.md): one ordinary handoff dropoff,
   one where you intentionally navigate the last leg on Google Maps. Add a matching
   `Next field test` checklist item (watch the shadow `navExitGated` flag;
   Confirmed 0/2).
5. **Only then** flip the guard to actually suppress the premature ARRIVED, and add
   the "Arriving soon" matcher for the enrichment state.

This sequences the win (premature-immunity) ahead of the risk (external-nav
fallback), and respects the alpha discipline of proving on-device before changing
live behavior.

---

## What June still can't answer (needs future captures)
- How often "Arriving soon" actually fires on-device (capture-cadence limited).
- External-nav incidence beyond the 2 dropoffs seen (is it common? pickup too?).
- Cash-on-delivery / signature / staff-handoff / locker — no June data (see the
  companion doc's gaps).
