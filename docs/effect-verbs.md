# Effect Verbs Reference

Rule author reference for the effect verb vocabulary. See
[ADR-0008](adr/ADR-0008-rule-driven-effect-system.md) for architecture.

## Declaring Effects in Rules

Add an `effects` array to any screen rule branch:

```json
{
  "id": "doordash.screen.offer",
  "branches": [{
    "intent": "offer_popup",
    "effects": [
      { "screenshot": { "prefix": "Offer - {storeName}", "category": "offer" },
        "dedupeKey": "offer-ss-{offerHash}", "throttleMs": 60000 },
      { "log": { "type": "OFFER_RECEIVED" } }
    ]
  }]
}
```

There is no `target` field on a branch — a branch's output name derives from the rule id
(`RuleCompiler.deriveTargetFromId`) unless `intent` is set explicitly. See ADR-0001.

## Effect Format

Each effect object has one **verb key** (the verb name) whose value holds
the verb's parameters (an object — every verb below takes an args object, never a bare
target string; there is no `click` verb, see Actuation below). Optional meta keys sit
alongside it:

| Key | Required | Description |
|---|---|---|
| *verb name* | Yes | The verb name is the key; value is an args object |
| `onlyIf` | No | Gate: only fire if parsed field matches condition |
| `dedupeKey` | No | Stable key for throttle deduplication |
| `throttleMs` | No | Minimum ms between firings of the same dedupeKey (default: 500) |

## Template Interpolation

Args values and `dedupeKey` support `{fieldName}` references. These are
resolved against the branch's parsed fields at match time.

- Resolution is one-pass (no recursion).
- Unknown fields are left literal: `{unknown}` stays as-is.
- Resolved values are sanitized: control chars stripped, capped at 256 chars.

## Actuation: there is no `click` verb

Rules cannot declare actuation (#425) — a rule-declared `click`/`tap`/`swipe`/`scroll`/
`set_text`/`long_click` effect is **rejected at compile time** with a migration error, not
silently ignored. This is a Pledge-level control: no rule (bundled or, eventually, remote)
can make the app tap a third-party app on its own say-so.

Instead, a screen rule exposes named **target bindings** in its `bind:` block —
`acceptButton`/`declineButton`/`expandButton` are the well-known names the app recognizes
(see ADR-0001 § Bind semantics):

```json
{
  "bind": {
    "acceptButton": { "find": { "hasIdSuffix": "accept_button" } }
  }
}
```

The app-owned `RuleAction` registry (`:domain`) — not the rule — decides *whether and when*
to tap: it emits `AppEffect.PerformRuleAction(action, platform, target, ruleId)` from the
state machine/EffectMap, and `UiInteractionHandler.performVerifiedClick` (`:app`) is the only
code path that ever taps a third-party app, gated on a granted capability (content-pinned to
the binding's own definition, #422), package scope, and label verification. A platform
supports an action iff its ruleset binds the matching name — adding taps for a new platform
is a rules change, not an app release. See `docs/design/rule-capability-consent.md`.

## Verb Reference

### Observation-Driven Verbs (no built-in default)

#### `screenshot`
Capture the current screen.

| Property | Value |
|---|---|
| Permission | ACCESSIBILITY |
| Allowed args | `prefix`, `category` |

`category` names the Evidence Locker consent bucket (`offer` / `delivery_summary` /
`dash_summary`) the capture is gated on — **required in practice**: a screenshot effect
without a `category` is always suppressed (#426).

```json
{ "screenshot": { "prefix": "Offer - {storeName}", "category": "offer" } }
```

#### `bubble`
Post a message to the bubble HUD.

| Property | Value |
|---|---|
| Permission | NONE |
| Allowed args | `text`, `persona` |

Persona values: `dispatcher`, `system`, `earnings`, `inspector`, `navigator`,
`shopper`, `good_offer`, `bad_offer`.

```json
{ "bubble": { "text": "Offer Accepted", "persona": "dispatcher" } }
```

#### `log`
Write a structured log entry.

| Property | Value |
|---|---|
| Permission | NONE |
| Allowed args | `type`, `payload` |

```json
{ "log": { "type": "OFFER_RECEIVED", "payload": "pay={payAmount}" } }
```

#### `evaluate_offer`
Trigger offer evaluation. Currently requires rich ParsedOffer data — fires
from EffectMap, not from rule effects.

| Property | Value |
|---|---|
| Permission | NONE |
| Allowed args | (none) |

#### `speak`
Speak offer details via TTS. Currently requires rich ParsedOffer data.

| Property | Value |
|---|---|
| Permission | AUDIO |
| Allowed args | `text`, `platform` |

### Lifecycle Verbs (have built-in defaults, overridable)

These fire automatically on state transitions unless overridden via
`transitionOverrides`.

#### `session_start` / `session_end`
Start or end a platform session.

| Property | Value |
|---|---|
| Permission | NONE |
| Allowed args | `platformName` |

#### `odometer_start` / `odometer_stop` / `odometer_pause` / `odometer_resume`
Control mileage tracking.

| Property | Value |
|---|---|
| Permission | LOCATION |
| Allowed args | (none) |

#### `schedule_timeout` / `cancel_timeout`
Manage state machine timers.

| Property | Value |
|---|---|
| Permission | NONE |
| Allowed args (schedule) | `type`, `durationMs` |
| Allowed args (cancel) | `type` |

Timeout types: `SESSION_PAUSED_SAFETY`, `RETRY_CLICK`, `SETTLE_UI`,
`DECLINE_POPUP_WAIT`, `SCREENSHOT_WAIT`.

## Transition Overrides

Declare `transitionOverrides` on a branch to replace built-in defaults for
a transition trigger:

```json
{
  "transitionOverrides": {
    "mode:online": [
      { "session_start": { "platformName": "Uber" } },
      { "odometer_start": {} },
      { "log": { "type": "SESSION_START" } }
    ]
  }
}
```

### Available Triggers

| Trigger | Fires when |
|---|---|
| `mode:online` | Platform transitions to online mode |
| `mode:paused` | Platform transitions to paused mode |
| `mode:offline` | Platform transitions to offline mode |
| `job:start` | New job begins (offer accepted) |
| `job:completed` | All active work done, returning to idle |
| `task:start` | New task detected (pickup or dropoff) |
| `task:arrived` | Arrived at task location |
| `task:completed` | Individual task completed |
| `resume:from_pause` | Resuming from paused state |

Override **replaces** the default effects for that trigger (does not merge).

## Conditional Effects (onlyIf)

Gate an effect on a parsed field value:

```json
{
  "bubble": { "text": "Offer Accepted", "persona": "dispatcher" },
  "onlyIf": { "fieldEquals": { "field": "intent", "value": "accept" } }
}
```

Supported gates: `fieldEquals`, `fieldNotEquals`, `fieldNotNull`.
