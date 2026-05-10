# ADR-0008: Rule-Driven Effect System

**Status:** Implemented
**Date:** 2026-05-09
**Supersedes:** ADR-0006 (rule-originated UI actions — click-only)
**Builds on:** ADR-0001, ADR-0005

---

## Context

ADR-0006 introduced rule-originated actions with a single verb (`click`).
All other side effects — screenshots, bubble messages, offer evaluation,
odometer lifecycle, session management, timeout scheduling — were hardcoded
in `EffectMap.kt` (596 lines of if/when Kotlin logic). This meant:

- Adding a new platform required editing Kotlin code, not just JSON rules.
- Per-platform behavior (e.g., "Uber says 'Trip started'" vs. "DoorDash says
  'Dash started'") required conditional branching in app code.
- The effect vocabulary was implicit — new effects required changes across
  multiple files with no compile-time validation.

## Decision

Replace the single-verb action system with a **complete effect verb registry**
that covers every side effect the state machine can produce. Rules declare
effects using this vocabulary; the state machine provides built-in defaults
for lifecycle transitions that rules can override per-platform.

### Design Principles

1. **Every effect verb is declared in the registry** — nothing hidden or implicit.
2. **Built-in defaults fire unless a rule explicitly overrides** for a given
   transition trigger. Override replaces (not merges).
3. **Template interpolation is one-pass, whitelist-only** — no recursive
   resolution, no arbitrary field access.
4. **Unknown verbs, malformed args, and ungated privileged verbs are rejected
   at compile time.**
5. **Effects ride the existing Observation pipeline** — preserves replay
   determinism.

### Verb Registry (14 verbs)

| Verb | Wire | Tier | Requires Target | Has Default |
|---|---|---|---|---|
| CLICK | `click` | ACCESSIBILITY | Yes | No |
| SCREENSHOT | `screenshot` | ACCESSIBILITY | No | No |
| BUBBLE | `bubble` | NONE | No | No |
| LOG | `log` | NONE | No | No |
| EVALUATE_OFFER | `evaluate_offer` | NONE | No | No |
| SPEAK | `speak` | AUDIO | No | No |
| SESSION_START | `session_start` | NONE | No | Yes |
| SESSION_END | `session_end` | NONE | No | Yes |
| ODOMETER_START | `odometer_start` | LOCATION | No | Yes |
| ODOMETER_STOP | `odometer_stop` | LOCATION | No | Yes |
| ODOMETER_PAUSE | `odometer_pause` | LOCATION | No | Yes |
| ODOMETER_RESUME | `odometer_resume` | LOCATION | No | Yes |
| SCHEDULE_TIMEOUT | `schedule_timeout` | NONE | No | Yes |
| CANCEL_TIMEOUT | `cancel_timeout` | NONE | No | Yes |

### Permission Tiers

- **NONE** — always allowed (bubble, log, session lifecycle, timeouts)
- **ACCESSIBILITY** — requires accessibility service (click, screenshot)
- **LOCATION** — requires location permission (odometer)
- **AUDIO** — requires audio output (TTS)

### Transition Triggers (9 triggers)

| Trigger | Wire | Default Verbs |
|---|---|---|
| MODE_TO_ONLINE | `mode:online` | session_start, odometer_start, log |
| MODE_TO_PAUSED | `mode:paused` | schedule_timeout, log, bubble |
| MODE_TO_OFFLINE | `mode:offline` | session_end, odometer_stop, log |
| JOB_START | `job:start` | log |
| JOB_COMPLETED | `job:completed` | log |
| TASK_START | `task:start` | odometer_resume, log, bubble |
| TASK_ARRIVED | `task:arrived` | odometer_pause, log |
| TASK_COMPLETED | `task:completed` | log |
| RESUME_FROM_PAUSE | `resume:from_pause` | cancel_timeout |

### Effect Flow Through the Pipeline

```
Rule JSON               CompiledEffect          RequestedEffect         AppEffect.RequestEffect
  effects: [{...}]  -->  compileEffectEntry  -->  resolveEffects     -->  diffRuleEffects
  verb, args, target     verb validated           template resolved       gate evaluated
  dedupeKey, throttle    args validated            binding resolved        throttle checked
                         target enforced                                   dispatched by verb
```

### Template Interpolation

Effect args and dedupeKey support `{fieldName}` references resolved against
the branch's parsed fields at match time.

- **One-pass**: resolved values are not re-scanned for `{...}` patterns.
- **Whitelist**: only field names from the branch's parser output are available.
- **Sanitized**: control characters stripped, values capped at 256 chars.
- **Unknown fields**: left as literal `{fieldName}` (not an error).

Example: `"prefix": "Offer - {storeName}"` resolves to `"Offer - Chipotle"`.

### Transition Override Semantics

Rules can declare `transitionOverrides` to replace built-in defaults for
specific triggers on their platform:

```json
{
  "transitionOverrides": {
    "mode:online": [
      { "verb": "session_start", "args": { "platformName": "Uber" } },
      { "verb": "odometer_start" }
    ]
  }
}
```

When present, the override **replaces** (not merges) the default effect set.
Override effects go through the same validation as observation effects.

## Security Model

| Threat | Mitigation |
|---|---|
| Unknown verb injection | `EffectVerb.fromWire()` rejects at compile time |
| Unknown arg keys | Per-verb `ALLOWED_ARGS` whitelist at compile time |
| Template injection | One-pass resolution, no recursion |
| Control char injection | `sanitizeTemplateValue()` strips control chars |
| Oversized args values | 256-char cap on resolved template values |
| Privileged verb without permission | `PermissionTier` check before execution |
| Unknown transition trigger | `TransitionTrigger.fromWire()` rejects at compile time |

## Consequences

### Positive

- Platform-specific effects are defined in JSON, not Kotlin. Adding Uber
  effects requires no app code changes.
- The verb registry is exhaustive — the compiler enforces coverage.
- Template interpolation eliminates hardcoded string formatting in EffectMap.
- Transition overrides enable per-platform lifecycle customization.
- Permission tiers provide defense-in-depth for privileged verbs.

### Negative

- EVALUATE_OFFER and SPEAK still require rich objects (ParsedOffer), so they
  remain as hardcoded AppEffects in EffectMap for now.
- Template interpolation adds a small allocation per effect per screen event.
  Measured as negligible on target device.

### Future Work

- Migrate EVALUATE_OFFER and SPEAK to rule effects when the SideEffectEngine
  can pull ParsedOffer from state.
- Add effects support to click and notification rulesets.
- DataStore-backed EffectPermissionPrefs for user-facing permission UI.
- Flow coverage warnings at compile time for non-dev rulesets.
