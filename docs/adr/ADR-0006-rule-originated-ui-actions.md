# ADR-0006: Rule-Originated UI Actions

**Status:** Superseded by [ADR-0008](ADR-0008-rule-driven-effect-system.md)
**Date:** 2026-05-02
**Builds on:** ADR-0001, ADR-0005
**Related:** Bug bundle issue #220 (Bugs 8a, 8c, 9)

---

## Context

The state machine consumes a platform-agnostic intermediate representation
(`flow`, `modeHint`, `parsed`). It is intentionally insulated from
platform-specific UI concerns: it should not know which screen a DoorDash
post-task earnings expansion lives behind, nor what the resource ID of the
"expand" button is. That separation is the entire reason the `Platform` enum
is the only place platform identity exists as a type in `:domain`.

But some data the app needs is only surfaced after a UI interaction. The
clearest example today: on DoorDash's post-task / earnings screen, the tip
breakdown is hidden until a specific node is tapped. Previously, the app
auto-tapped that node so the expanded breakdown could be parsed. In the
ADR-0005 refactor that behavior is no longer firing, and there is no
architecturally-clean home for it: putting it in the state machine
reintroduces platform-specific dispatch into a layer that should never see
platform identity; putting it in a side-effect handler hard-codes DoorDash
node IDs into app code, which defeats the OTA story from ADR-0001.

The same shape of problem will recur on every gig platform we add. Each
platform has its own UI quirks where data is one tap, scroll, or longer
interaction away. We need a way to express *"when you recognize this
screen, also perform this UI action"* that:

- keeps platform-specific knowledge inside the rule file (Layer 2),
- leaves the state machine (Layer 3) seeing only opaque, platform-agnostic
  intent,
- is OTA-updatable when a platform changes its node IDs,
- has the same closed-vocabulary safety posture as transforms and assertions,
- is replay-safe, dedupe-safe, and loop-safe.

This ADR adopts that capability and names what it has to do without
prescribing exactly how it lives in code.

---

## Decision

A matcher rule may declare **actions** as a peer of `parse:` and `validate:`.
When the rule matches, the engine emits the rule's parsed IR *and* a list of
requested UI actions. Action dispatch flows through the same effect plumbing
the state machine already uses for everything else: the state machine sees
actions as opaque effect intent and does not interpret them.

Action intent is declarative data, not code. The set of action verbs is
closed and engine-owned, mirroring `TransformRegistry` from ADR-0001. Rule
files reference verbs by name; unknown verbs fail at compile time. New
verbs require an app update.

---

## What we want to happen

Behaviors we are committing to, expressed as outcomes rather than
implementation.

### 1. Rules can ask for a UI action.

A rule that has matched can request one or more UI actions targeted at nodes
the rule already bound. The actions are part of the rule's declared output;
they are not handed off to a separate file or system.

### 2. The state machine does not interpret actions.

The state machine receives the action list as part of the observation,
treats it as opaque, and routes it through the existing per-region effect
pipeline as a request. The state machine never inspects the verb, the
target, or platform-specific arguments.

### 3. Action verbs are a closed, engine-owned vocabulary.

There is a registered, enumerated set of action verbs (e.g. *click,
long-click, scroll-to, dismiss, input-text*). Rule files reference verbs by
name. Unknown verbs are rejected at rule-compile time. The set is auditable
in one place. Adding a verb is an app change, not a rule change. Rule files
cannot expand the executable surface.

### 4. Action targets are resolved at match time.

When a rule binds a node and references it as an action target, the
resolution happens in the same pass as parsing — the engine captures a
stable reference (node id plus a content fingerprint, not a re-evaluatable
predicate). If the node is gone by the time the action is dispatched, the
executor logs and skips. The state machine never holds a live `UiNode`
reference.

### 5. Actions are idempotent across replays.

Actions participate in the same effect-key idempotency mechanism the rest
of the side-effect engine uses (ADR-0005 §6.4 and §7.4). A rule may declare
a dedupe key; effects sharing a key are coalesced. During crash recovery,
action effects are treated as external and suppressed — we never re-tap
something we already tapped before the crash.

### 6. Actions do not loop.

The system must not produce an action-event-action-event cycle when the act
of dispatching the action causes a re-match of the same rule. Two
complementary guards exist:

- **State gating.** A rule may condition an action on parsed state — "click
  expand only if `isExpanded == false`." Once the action lands and the
  screen redraws, the gate naturally closes. This is the preferred form
  because it is data-driven and survives crash recovery.
- **Throttling / scope caps.** A rule may declare a minimum interval, or a
  once-per-scope cap (`oncePer: "task"`, `oncePer: "session"`), as a
  backstop.

Whichever combination a rule uses, absence of either must not be a footgun:
a rule with no gate and no throttle still cannot loop, because the
dispatcher enforces a minimum default interval per `(ruleId, target)`.

### 7. Actions are visible in captures.

When a capture envelope is written (ADR-0005 §3), the requested action list
for that match is included. Replay tests can therefore assert "rule X on
this UI tree produces these N actions," giving the matchers repository the
same fidelity for actions as it has for parsed fields today.

### 8. Action verbs carry privilege tiers.

Some verbs (click, scroll-to) are low-risk; others (input-text, long-click
on arbitrary nodes) are higher-risk. The registry classifies verbs by
privilege. Rules that invoke a high-privilege verb must be
`overrideable: false`, mirroring the SensitiveScreenMatcher pattern:
community rule contributions distributed via OTA cannot introduce
high-privilege actions; only rules baked into the bundled defaults can.

### 9. The platform layer stays unchanged.

Adding action support does not change the `:domain` platform-agnosticism
rule. Action *verbs* are generic ("click"). Action *targets* are
platform-specific only inside the rule file, where they belong. The state
machine, the cross-platform region, and the bubble HUD do not gain any
platform-specific code as a result of this ADR.

---

## DSL surface (illustrative)

The exact shape is a Phase-1 design task, but the rough surface looks like:

```json5
{
  id: "doordash.screen.post_task.collapsed",
  bind: {
    expandButton: { find: { id: "expand_pay_breakdown" } },
  },
  require: { /* ... */ },
  parse: {
    fields: {
      totalPay:   { /* ... */ },
      isExpanded: { presence: { exists: { id: "doordash_pay_label" } } },
    },
  },
  actions: [
    {
      command: "click",
      target:  "$expandButton",
      onlyIf:  { fieldEquals: { field: "isExpanded", value: false } },
      dedupeKey: "expand_pay_breakdown",
      throttleMs: 1000,
    },
  ],
}
```

`onlyIf` references parsed fields; `target` references bound nodes;
`command` references registered verbs. None of these introduce new
evaluation primitives — they reuse vocabulary already established for parse
and predicates.

---

## Non-goals

- **No specific Kotlin shape mandated.** The IR addition (a list of
  requested actions on the matched observation), the effect addition (a
  request-action effect type), and the executor (a side-effect handler
  against the accessibility service) are described as roles, not as
  concrete classes. Whoever implements this picks the cleanest fit against
  the actual ADR-0005 codebase as it exists when the work begins.
- **No new pipeline.** Actions ride on the existing observation → state
  machine → effect → side-effect path. No new layer.
- **No new persistence.** Actions are transient effects. The observation
  log records that an action was requested (via the captured envelope); the
  action itself is not separately durable.
- **No platform-specific code in `:domain` or the state machine.** If
  implementation pressure pushes any platform identity past Layer 2, that
  is a defect, not an acceptable shortcut.

---

## Open questions

Flagged for whoever implements the first phase:

1. **Default loop guard interval.** Per `(ruleId, target)`, what is the
   minimum interval the dispatcher enforces in absence of a rule-declared
   throttle? Suggested starting point: 500ms, tunable.
2. **Verb privilege boundaries.** Initial low-privilege set: click,
   scroll-to. Initial high-privilege set: input-text, long-click,
   dismiss-on-root. Refine before shipping.
3. **Once-per-scope semantics.** What scopes are exposed — `task`, `job`,
   `session`, `correlationVersion`? Pick the smallest set that covers real
   cases; expand only if a rule needs more.
4. **Action result feedback.** Today, the dispatcher fires and forgets. If
   a rule needs to know the action succeeded (e.g. to advance state), do we
   want a `Loopback` observation (per ADR-0005 §4.1) carrying the
   success/failure? Defer until a real use case forces it.
5. **Capture envelope shape.** Where in the envelope do requested actions
   live — alongside `parsed`, or in a sibling `actions` field? Cosmetic;
   pick during Phase 1.

---

## Bugs this addresses

From issue #220 (on-dash testing bug bundle, 2026-05-02):

- **Bug 8a** — DoorDash post-task tip-expansion auto-tap not firing.
  Becomes a one-line addition to the post-task rule.
- **Bug 8c** — Cross-platform architectural question about where
  platform-specific UI interaction lives. Answered: in the rule file,
  expressed via `actions`.
- **Bug 9** — Post-delivery earnings message duplicated. The same
  `dedupeKey` mechanism that makes the auto-tap fire once also lets the
  post-delivery message emit once per delivery, without per-handler ad-hoc
  deduplication.

---

## Cross-references

- **ADR-0001** — matcher rule format; `TransformRegistry` is the precedent
  pattern this ADR mirrors for the action verb registry.
- **ADR-0005** — pipeline + multi-region state architecture; this ADR adds
  one field to the matched observation, one effect type to the effect map,
  and one handler at the side-effect layer, all within boundaries §13
  already permits.
- **#220** — bug bundle motivating this ADR.
