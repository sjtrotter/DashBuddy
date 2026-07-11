# ADR-0008: Rule-Driven Effect System

**Status:** Implemented
**Date:** 2026-05-09
**Supersedes:** ADR-0006 (rule-originated UI actions — click-only)
**Builds on:** ADR-0001, ADR-0005

---

> **Implementation status (2026-07-11, #750).** #425 (landed after this ADR was written) removed
> rule-declared actuation entirely: the compiler now **rejects** the `click` verb (and
> `tap`/`swipe`/`scroll`/`set_text`/`long_click`) at compile time. #750 re-verified this ADR
> against the current compiler (`EffectVerb` in `:domain`, `EffectEntryCompiler` in
> `:core:pipeline`) and corrected the verb registry, the effect-flow note, and the permission-tier
> table below to match — the compiler is the source of truth; this ADR describes it, never the
> reverse. Corrections are made in place rather than deleted — the status-note + in-place-correction
> pattern from ADR-0001's 2026-07-10 revision — with the removed verb's original entry additionally
> struck through and annotated (new with this revision; no earlier ADR uses strikethrough) so the
> pre-#425 design stays legible as history:
>
> | Area | Status |
> |---|---|
> | Verb registry (all verbs except `click`), permission tiers other than the ACCESSIBILITY row, transition triggers, template interpolation, transition overrides | **Implemented as originally described** — see `EffectVerb.kt`, `EffectEntryCompiler.kt` |
> | `click` verb | **Removed (#425).** Rules can no longer declare actuation of any kind — `EffectEntryCompiler.compileEffectEntry` rejects the wire values `click`/`tap`/`swipe`/`scroll`/`set_text`/`long_click` *before* verb lookup even runs. In its place, a screen rule's `bind:` block exposes well-known **target bindings** — `acceptButton`, `declineButton`, `confirmDeclineButton`, `expandButton` — that the app-owned `RuleAction` registry (`:domain`) consumes to perform verified taps. See `docs/design/rule-capability-consent.md` and `docs/effect-verbs.md` (both current) for the replacement model. |
>
> The corrections this revision made are tracked in #750 (closed by this edit); #744/#241 did the
> equivalent pass for ADR-0001.

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
4. **Unknown verbs and malformed args are rejected at compile time.** Privileged
   verbs compile — the compiler *collects* their required tiers (`requiredTiers`);
   the gate is at runtime, before execution (see Security Model).
5. **Effects ride the existing Observation pipeline** — preserves replay
   determinism.

### Verb Registry (13 verbs; originally proposed with 14 — see status note above)

The "Requires Target" column below is struck through: it applied only to the now-removed `click`
verb below (a bare target-string form). No verb in the current `EffectVerb` registry has a
target-string form or a "requires target" attribute at all — every verb takes an args object
(see Effect Flow below).

| Verb | Wire | Tier | ~~Requires Target~~ | Has Default |
|---|---|---|---|---|
| ~~CLICK~~ — **REMOVED (#425)**, see status note above | ~~`click`~~ | ~~ACCESSIBILITY~~ | ~~Yes~~ | ~~No~~ |
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

SCREENSHOT is now the **only** `EffectVerb` on the ACCESSIBILITY permission tier (see Permission
Tiers below) — with CLICK gone, nothing else in the registry requires the accessibility service.

### Permission Tiers

- **NONE** — always allowed (bubble, log, evaluate_offer, session lifecycle, timeouts)
- **ACCESSIBILITY** — requires accessibility service (~~click,~~ screenshot — `click` was removed
  from the `EffectVerb` registry by #425; SCREENSHOT is the only verb left on this tier. The
  accessibility-service tier concept still applies to *actuation* — `RuleAction` taps still check
  the live accessibility-service handle — it's just enforced on the separate `RuleAction`/
  `UiInteractionHandler` path now, not via any `EffectVerb`. See the status note at the top of
  this document.)
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
  verbKey: params        verb validated           template resolved       gate evaluated
  dedupeKey, throttle    args validated            binding resolved        throttle checked
                                                                          dispatched by verb
```

The verb name is the JSON key; its value holds the parameters directly as an args object. (The
original design had a second form — a bare target string for `click` — but `click` was removed
by #425, so every verb in the current registry uses the args-object form; see `docs/effect-verbs.md`
§"Effect Format" — "every verb below takes an args object, never a bare target string.")

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
      { "session_start": { "platformName": "Uber" } },
      { "odometer_start": {} }
    ]
  }
}
```

When present, the override **replaces** (not merges) the default effect set.
Override effects go through the same validation as observation effects.

## Security Model

| Threat | Mitigation |
|---|---|
| Actuation verb injection (`click`/`tap`/`swipe`/`scroll`/`set_text`/`long_click`) | `EffectEntryCompiler`'s `rejectedActuationWires` check rejects at compile time — **before** `EffectVerb.fromWire()` even runs (#425, added after this ADR was written) |
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
- ~~Add effects support to click and notification rulesets.~~ **Shipped since** (annotated by #750):
  click/notification rules carry `effects`/`transitionOverrides` (`RuleCompiler` allows the keys for
  both contexts, `ObservationClassifier` attaches the resolved effects, `EffectMap.diffRuleEffects`
  fires them for any `FlowObservation`).
- DataStore-backed EffectPermissionPrefs for user-facing permission UI.
- Flow coverage warnings at compile time for non-dev rulesets.
