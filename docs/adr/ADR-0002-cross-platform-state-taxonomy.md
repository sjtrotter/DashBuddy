# ADR-0002: Cross-Platform State Taxonomy in Matcher Rules

**Status:** Accepted
**Issue:** Sub-RFC of Epic #192; references #211, #214, #215
**Date:** 2026-04-30
**Builds on:** ADR-0001 (Matcher / Classifier Rule Format for OTA Updates)

---

## Context

ADR-0001 defines how the rule format identifies which screen the dasher is on
and extracts structured fields from it. It does not define how the screen is
mapped onto the application's state machine. Today, that mapping
(`PICKUP_NAVIGATION → OnPickup`, `OFFER_POPUP → OfferPresented`, etc.) lives
in `StateManagerV2` as Kotlin code.

This is fine for a single-platform app. It defeats the multi-platform pillar.

The multi-platform goal (#85, #86, #87, #158, #192) is for a single in-app
state machine to consume rules from any gig platform — DoorDash, Uber, Instacart,
Walmart Spark — without requiring a Play Store release per platform. If the
screen-to-state mapping is in Kotlin, then adding Uber requires an APK update
to teach the state machine that `PICKUP_DRIVING_TO_RESTAURANT` means OnPickup.
The OTA distribution channel only solves *part* of the integration cost; the
remaining Kotlin glue still gates new platforms behind Play Store review.

The fix is to move the screen-to-state mapping out of Kotlin and into the rule
file, expressed in **platform-agnostic vocabulary**. The state machine consumes
that vocabulary; it never sees DoorDash-specific or Uber-specific screen names.
Each platform ruleset is a frontend that translates native UI into a shared
intermediate representation; the state machine is the backend operating on the
IR. Adding a new platform = ship a new ruleset, no APK update for the state
machine itself.

---

## Decision

### The IR architecture

```
[Platform-specific UI]
    │  (e.g. DoorDash AccessibilityNodeInfo tree)
    ▼
[Per-platform DSL ruleset]
    │  (e.g. doordash.rules.json — interprets the UI)
    ▼
[Platform-agnostic IR event]
    │  ScreenInfo { stateHint, parsedData }
    ▼
[State machine — platform-agnostic Kotlin]
    │  consumes IR, manages transitions, fires side effects
    ▼
[App state, side effects, UI]
```

The DSL is the translation boundary. Each rule emits two pieces of IR:

1. **State hint** — where the dasher is in the gig flow, in shared vocabulary
2. **Parsed data** — structured fields per the output schema contract (see
   #211 item 26)

The state machine never knows which platform produced an event. Cross-platform
by construction.

### The taxonomy: two orthogonal axes

State is expressed as the product of two independent dimensions.

**`mode`** — the dasher's current connection / availability posture:

| Value | Meaning |
|---|---|
| `offline` | Not connected to any platform; app launched but dasher hasn't started a session |
| `online` | Connected and available (whether actively in a leg or not) |
| `paused` | Connected but temporarily unavailable (e.g., DoorDash "Dash Paused", Uber pause toggle) |

**`flow`** — what the dasher is currently doing in the gig flow:

| Value | Meaning |
|---|---|
| `idle` | Online but not in any active leg or offer |
| `offer:presented` | An offer is on screen awaiting accept/decline |
| `leg:pickup:navigation` | En route to a pickup location |
| `leg:pickup:arrived` | At a pickup location; specific activity (shopping, waiting) carried in parsed data |
| `leg:dropoff:navigation` | En route to a dropoff location |
| `leg:dropoff:arrived` | At a dropoff location; completing handoff |
| `post:leg` | Between legs or post-delivery (summary screens, ratings, etc.) |

Mode and flow are orthogonal: a dasher can be `(mode: paused, flow: idle)` or
`(mode: online, flow: leg:pickup:navigation)`. They update independently. A
`DashPausedMatcher` rule sets `mode: paused` without disturbing `flow`. A
`PickupNavigationMatcher` updates `flow` without disturbing `mode`.

### Sub-substates → parsed data, not deeper nesting

The taxonomy stops at the leaf values above. Activity refinements within a
leaf state — *shopping vs. waiting at pickup, single-order vs. multi-order
delivery, scanning a card vs. dropping at door* — are expressed as **parsed
data fields**, not deeper state nesting.

Rationale: deeper nesting balloons the cross-platform vocabulary
(`leg:pickup:arrived:shopping:scanning_card` becomes platform-specific
quickly), and the distinctions are usually carried by data anyway. A rule for
DoorDash's `PICKUP_DETAILS_POST_ARRIVAL_SHOP` sets
`flow: "leg:pickup:arrived"` and includes `activity: "shopping"` in its parsed
fields. Uber's equivalent rule emits the same flow with whatever activity tag
is meaningful for that platform. The state machine consumes both as
"`leg:pickup:arrived`, with platform-specific activity context in the data."

### The `state:` field shape

Each rule may declare a `state:` block:

```json5
{
  id: "doordash.screen.pickup_navigation",
  platform_id: "doordash.driver",
  priority: 40,
  overrideable: true,
  target: "PICKUP_NAVIGATION_TO_PICK_UP",
  state: { flow: "leg:pickup:navigation" },
  if: { /* matcher predicate */ },
  parse: { /* fields */ }
}
```

```json5
{
  id: "doordash.screen.dash_paused",
  platform_id: "doordash.driver",
  priority: 10,
  overrideable: true,
  target: "DASH_PAUSED",
  state: { mode: "paused" },
  if: { /* matcher predicate */ },
  parse: { /* fields */ }
}
```

```json5
{
  id: "doordash.screen.pickup_arrival_shop",
  platform_id: "doordash.driver",
  priority: 8,
  overrideable: true,
  target: "PICKUP_DETAILS_POST_ARRIVAL_SHOP",
  state: { flow: "leg:pickup:arrived" },
  if: { /* matcher predicate */ },
  parse: {
    fields: {
      activity: { fallback: "shopping" },   // platform-agnostic activity tag
      itemCount: { /* ... */ },
      redCardTotal: { /* ... */ }
    }
  }
}
```

Rules declare only the dimensions they know for certain. A rule that sets
`mode: paused` says nothing about `flow`; the state machine retains the
previously-known `flow` value. A rule that sets only `flow` leaves `mode`
unchanged.

A rule with **no `state:` field** does not contribute to state — it is purely
informational (e.g., sensitive screens, irrelevant transient screens). The
default behavior is "no state change," which fails safe.

### `state:` is a sensitive field

Misclassifying a screen via a wrong `if:` predicate misclassifies one screen.
Misclassifying via a wrong `state:` cascades — wrong side effects fire, wrong
timers start, wrong DB writes happen, the bubble HUD shows the wrong info.

Mitigations:

1. The vocabulary (mode and flow values) is enumerated in `:domain` as a
   sealed class. Rules cannot invent new state values; the JSON Schema
   (planned in #214) enforces this at author time.
2. Rules that change the `state:` of an existing rule are flagged for
   stricter review in CI / PR gating.
3. The interpreter rejects unknown mode/flow values at compile time with a
   clear `RuleCompileException` carrying the rule id.
4. `overrideable: false` is orthogonal to `state:` enforcement. A rule may be
   `overrideable: true` for its `if:` predicate but the *vocabulary* of state
   values it can use is fixed by the app's bundled enum.

---

## What stays in Kotlin

The DSL declares **where** the dasher is. The state machine remains in Kotlin
and owns **how transitions happen**.

| Concern | Layer |
|---|---|
| Screen → state hint mapping | DSL (`state:` field) |
| Parsed data extraction | DSL (`parse.fields`) |
| Transition guards (valid from-states, prerequisite parsed fields) | Kotlin (StateManagerV2) |
| Side effects (DB writes, notifications, timer starts, odometer updates) | Kotlin (effect handlers) |
| Inferred transitions (no screen event, but a timer fired) | Kotlin (TimeoutHandler) |
| State persistence | Kotlin (Room DAOs) |

Putting transition logic in DSL would make the state machine data-driven,
which would put the dasher's economic computation at the mercy of a malformed
rule. That is the same trust-boundary argument as the 5-phase pipeline
ordering in ADR-0001 §"Security Considerations" — some invariants are
security-equivalent and must not be expressible in OTA-pushed data.

---

## Migration path

This is a refactor of meaningful scope. The migration is staged so each step
is independently shippable.

### Phase B1: Introduce the IR layer (translation only)

1. Define the `mode` and `flow` sealed classes in `:domain`.
2. Add `state:` field to the rule schema; update `RuleCompiler` to parse and
   validate it.
3. Build a translation layer: `(mode, flow) → existing AppStateV2 sealed-class
   subtype`. The state machine code does not change in this phase; the
   translation layer is the only new code path.
4. Annotate every rule in `rules.default.json` with a `state:` field matching
   its current Kotlin-mapped state.
5. Run the parity test (#213) extended to also verify the IR translation
   produces the same `AppStateV2` as the existing direct mapping.

After Phase B1, the IR is fully populated but the state machine still operates
on `AppStateV2` sealed classes. Cross-platform support is mechanically
possible (a new platform ruleset just needs `state:` fields in shared
vocabulary), but the consuming state-machine code is unchanged.

### Phase B2: Collapse to the dimensional state model

This is the bigger refactor and is **not** required for the multi-platform
goal. It is the natural endpoint once the taxonomy has been validated through
B1.

1. Replace the 9-class `AppStateV2` sealed-class hierarchy with a record:
   ```kotlin
   data class AppState(
       val mode: Mode,
       val flow: Flow,
       val data: ParsedData,    // structured per the #211 item 26 schema
   )
   ```
2. Refactor effect handlers to dispatch on `(previousState, nextState)`
   transition tuples rather than sealed-class type matching.
3. Remove the translation layer from B1; the state machine consumes the IR
   directly.

Phase B2 is a cleaner endpoint but is independent in time — could land months
after B1, or never. The architecture is correct either way.

### Phase B3 (out of scope, future): platform-specific extensions

Some platforms may introduce flow shapes the current taxonomy doesn't cover
(e.g., a "stack of orders" model where multiple pickups precede a single
dropoff sweep). Adding a new flow value is an APK-shipped change that bumps
the rule schema's `format_version`. New flow values are auditable additions
to the `:domain` enum, not OTA primitive growth.

---

## Non-goals

- **Not state-machine logic in DSL.** Transition guards, side effects, and
  inferred transitions stay Kotlin. The DSL declares the *destination* of a
  transition (via `state:`), not the *transition function*.
- **Not unifying parsed data field names across platforms in this ADR.** The
  output schema contract (#211 item 26) is its own piece of work. This ADR
  reserves the `parsedData` slot but does not specify its shape.
- **Not deprecating `Screen` enum targets.** `target:` (the existing field
  pointing at a `Screen` enum) and `state:` (the new IR hint) coexist.
  `target:` is the platform-specific screen identity; `state:` is the
  cross-platform interpretation. Both are useful — `target:` for debugging,
  snapshot organization, and per-screen metrics; `state:` for the state
  machine.
- **Not redesigning side effects.** Effect handlers continue to dispatch on
  state transitions in Kotlin.

---

## Cross-references

- ADR-0001 — Matcher / Classifier Rule Format (the predicate language and rule
  schema that this ADR extends with `state:`)
- #87 — Original RFC behind ADR-0001
- #192 — Matchers infrastructure epic
- #211 — JSON rule interpreter audit (item 26 — output schema contract — is
  the parsed-data side of this IR)
- #214 — Contributor onboarding tooling (the JSON Schema and tutorial must
  cover `state:` once this lands)
- #215 — Structural parser rewrites (independent; parser shape doesn't depend
  on `state:` mapping)
- #85, #86, #158 — Multi-platform abstraction issues this ADR enables

---

## Decision summary

The DSL gains a `state:` field expressing where the dasher is in
platform-agnostic vocabulary (mode + flow). The state machine consumes this
IR; it never sees platform-specific screen names. Adding a new platform
becomes a ruleset shipped via OTA, not a Kotlin glue change shipped via Play
Store. Transition logic, side effects, and guards remain Kotlin. The
multi-dimensional state record is the natural long-term endpoint but is not
required for cross-platform support.
