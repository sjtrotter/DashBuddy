# Effect Verbs Reference

Rule author reference for the effect verb vocabulary. See
[ADR-0008](adr/ADR-0008-rule-driven-effect-system.md) for architecture.

## Declaring Effects in Rules

Add an `effects` array to any screen rule branch:

```json
{
  "id": "doordash.screen.offer",
  "branches": [{
    "target": "OFFER_POPUP",
    "effects": [
      { "screenshot": { "prefix": "Offer - {storeName}" },
        "dedupeKey": "offer-ss-{offerHash}", "throttleMs": 60000 },
      { "log": { "type": "OFFER_RECEIVED" } }
    ]
  }]
}
```

## Effect Format

Each effect object has one **verb key** (the verb name) whose value holds
the verb's parameters. Optional meta keys sit alongside it:

| Key | Required | Description |
|---|---|---|
| *verb name* | Yes | The verb name is the key; value is args object (or target string for `click`) |
| `onlyIf` | No | Gate: only fire if parsed field matches condition |
| `dedupeKey` | No | Stable key for throttle deduplication |
| `throttleMs` | No | Minimum ms between firings of the same dedupeKey (default: 500) |

## Template Interpolation

Args values and `dedupeKey` support `{fieldName}` references. These are
resolved against the branch's parsed fields at match time.

- Resolution is one-pass (no recursion).
- Unknown fields are left literal: `{unknown}` stays as-is.
- Resolved values are sanitized: control chars stripped, capped at 256 chars.

## Verb Reference

### Observation-Driven Verbs (no built-in default)

#### `click`
Tap a UI node identified by a binding.

| Property | Value |
|---|---|
| Permission | ACCESSIBILITY |
| Requires target | Yes |
| Allowed args | (none) |

```json
{ "click": "$acceptBtn" }
```

#### `screenshot`
Capture the current screen.

| Property | Value |
|---|---|
| Permission | ACCESSIBILITY |
| Requires target | No |
| Allowed args | `prefix` |

```json
{ "screenshot": { "prefix": "Offer - {storeName}" } }
```

#### `bubble`
Post a message to the bubble HUD.

| Property | Value |
|---|---|
| Permission | NONE |
| Requires target | No |
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
| Requires target | No |
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
| Requires target | No |
| Allowed args | (none) |

#### `speak`
Speak offer details via TTS. Currently requires rich ParsedOffer data.

| Property | Value |
|---|---|
| Permission | AUDIO |
| Requires target | No |
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
  "click": "$btn",
  "onlyIf": { "fieldEquals": { "field": "intent", "value": "accept" } }
}
```

Supported gates: `fieldEquals`, `fieldNotEquals`, `fieldNotNull`.
