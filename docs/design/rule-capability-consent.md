# Design: action targets, capabilities + load-time consent

**Status:** layers 1–3 and the capability refit implemented by #425 (2026-06-11);
the grant store + execution gate remain #417, the consent UI is #422 PR 3.
Supersedes the original draft of this doc (f9193c7, reconciled 51c58e1) and the
closed #85 ("GigPlatform interface") — see *History* at the bottom.

## The model: three layers

The core question is who may ever tap the third-party app, aimed by what, and
checked by whom. The answer is a strict split:

```
┌─ RECOGNITION ─────────── the ruleset (untrusted, forkable — #192) ──┐
│  "What's on screen?"                                                 │
│  → parses fields (pay $7.50, 3.2 mi)                                 │
│  → exposes named TARGET BINDINGS:  acceptButton  → NodeRef           │
│                                    declineButton → NodeRef           │
│                                    expandButton  → NodeRef           │
│  Pure observation. No decisions. No actuation. Just DATA.            │
└───────────────────────────────────────────────────────────────────────┘
              │ fields + targets (ride the Observation / PendingOffer)
              ▼
┌─ DECISION ────────────── the state machine (app-owned) ──────────────┐
│  Evaluation (OfferEvaluator × user economy) + policy + user input    │
│  (bubble / own-notification Accept-Decline) decide IF an action      │
│  fires. EffectMap owns the expand decision (collapsed summary +      │
│  bound target → settle → act). Rules can never reach in here.        │
└───────────────────────────────────────────────────────────────────────┘
              │ AppEffect.PerformRuleAction(action, platform, target, ruleId)
              ▼
┌─ ACTUATION ───────────── the executor (app-owned, verified) ─────────┐
│  RuleAction registry = the app-owned vocabulary (#425):              │
│    ACCEPT_OFFER / DECLINE_OFFER / EXPAND_EARNINGS                    │
│  consent gate (#417, pending) → throttle → package scope →           │
│  label verification → strict click (self/ancestor only)              │
└───────────────────────────────────────────────────────────────────────┘
```

**Rules cannot declare actuation at all.** The `click` effect verb was removed
from the schema and the compiler rejects it (and future gesture wires:
`swipe`, `scroll`, `set_text`, …) with a migration error. A rule's only way to
participate in a tap is to *bind a target* — data the app may or may not act
on.

**Targets are the platform contract.** `RuleAction.targetBindName` defines
well-known bind names (`acceptButton`, `declineButton`, `expandButton`). A
platform supports an action iff its ruleset binds that name on the relevant
screen; a missing binding means the action is unavailable there (fail to
manual — e.g. Uber today, whose offer overlay has no binds yet). This is what
makes "rulesets define platforms" literal: adding offer actions for a new
platform is a rules change, not an app release. (This supersedes #85's
per-platform Kotlin interface, which would have been N hardcoded button sets in
compiled code — the matcher-class pattern ADR-0001 abolished. The old
`offerActionNode()` hardcoded-ID path is deleted.)

## Consent: a static half and a dynamic half

Both halves are required; each catches what the other cannot.

### Static: the content-pinned capability key (load time)

A **RuleCapability** is one (rule, action) pair a ruleset's bindings enable —
the unit of user consent:

```
RuleCapability(
    ruleId: String,          // e.g. "doordash.screen.offer_popup"
    action: RuleAction,      // ACCEPT_OFFER | DECLINE_OFFER | EXPAND_EARNINGS
    targetBindName: String,  // display only
    key: String,             // the grant key — see below
    source: String,          // "asset:doordash.json" | "cdn:<url>" | "fork:<id>"
)

key = sha256( canonicalJson({
    "rule":   ruleId,
    "action": action.wire,
    "bind":   bindName,
    "def":    <the binding DEFINITION — its `find` predicate + flags>
}) )
```

Enumeration (`RuleCompiler.enumerateCapabilities`) walks the compiled rules'
bind blocks against the `RuleAction` registry. The key pins the binding
**definition**: a remote update to a trusted rule id that keeps the bind name
but repoints `declineButton`'s predicate at the Accept button changes the
definition → changes the key → the old grant no longer covers it and it
re-enters consent. Robustness (both tested): the key input is a canonical JSON
object (structurally unambiguous — no in-band delimiters), recursively
key-sorted (an innocuous reformat must not re-prompt; re-prompt fatigue trains
click-through).

**No key threading.** The original implementation stamped keys onto compiled
effects and carried them through the pipeline ("the same value by
construction") — and the carry chain silently dropped them in two places
(deferred-click reroute, transition overrides). That chain is deleted. The
future gate (#417) looks grants up at fire time from the enumeration, keyed by
the `sourceRuleId` + `action` that ride `PerformRuleAction` — authoritative
state, not threaded fields.

### Dynamic: tap-time verification (fire time) — implemented

The definition pin is necessary but not sufficient: a **byte-stable definition
can resolve to a different control** after a platform UI update (DoorDash owns
what `secondary_action_button_dash_plus` is), and the screen can change between
decision and tap. So the executor (`UiInteractionHandler.performVerifiedClick`)
re-checks the *live* resolution against anchors the ruleset cannot influence:

1. **Package scope** — only windows of `platform.packageName` are searched.
   No package → no tap.
2. **Label expectation** — `RuleAction.verification` is an app-owned pattern
   the resolved node's bounded subtree texts must satisfy (buttons label via
   child TextViews): ACCEPT_OFFER ⇒ `accept|add to route`, DECLINE_OFFER ⇒
   `decline`. EXPAND_EARNINGS is label-free (icon-only chevron) — package
   scope is its bar, acceptable for a read-only expansion tap. (Patterns track
   the platform app's display language — en-US alpha; locale work is #428.)
3. **Strict click** — self-or-ancestor only. The old clickable-*sibling*
   fallback is gone from this path: Accept sits beside Decline in the offer
   footer, and verification ran on *this* node's subtree.
4. **App-owned throttle** — `RULE_ACTION_THROTTLE_MS` per (action, platform)
   in the engine; ruleset content cannot loosen it.

Any check failing skips the tap and logs; the user acts manually. This
restores — in stronger, app-authored form — the runtime grounding the original
resolved-node key had (see *History*).

### What the user actually consents to (UI = #422 PR 3)

"DashBuddy may **tap Decline** for you on **DoorDash**" — per action, per
platform, pinned to the rule's current binding definition. Consent copy is
**app-owned string resources** (#428), never rule-supplied text: the rule
contributes only a bind name and predicate, neither of which is driver-legible
or trustworthy enough for a consent prompt. Trigger provenance matters for the
gate: user-initiated taps (bubble / own-notification `UiInput`) express intent
for that action; the *grant* check is for automation-initiated fires. Target
verification (the dynamic half) applies to **both** — integrity is never
skipped.

### Source policy (unchanged)

- **Asset source** (bundled rules the developer ships) → auto-grant on load;
  no consent friction in the single-user alpha.
- **CDN / fork source** (#192) → never auto-grant; every capability pending
  until approved. A downloaded rule can recognize screens immediately but
  cannot aim a tap without an explicit, content-pinned grant.

## Lifecycle (current state)

1. **Enumerate at load** — implemented (`enumerateCapabilities`).
2. **Partition against the grant store** — #417 (`RuleCapabilityDataSource` in
   `:core:datastore` → repository in `:core:data`, reactive `grantedHashes`).
   Pending ⇒ recognition still runs; the action is suppressed (fail closed).
3. **Consent surface** — #422 PR 3 (review screen + new-pending prompt; show a
   *diff* on re-consent so a legit update reads differently from a repoint).
4. **Execution gate** — #417, at the `PerformRuleAction` seam in
   `SideEffectEngine` (the tier check there is still the alpha always-true
   stub): OS tier AND grant-lookup by (sourceRuleId, action) AND the dynamic
   verification above. Any missing → log + skip.

## Known gaps / open decisions

1. **The decision surface is wider than the target.** The key pins the binding
   definition, not the rule's `parse`/`require` blocks — a fork could keep
   targets byte-identical and misparse $3.50 as $35.00, passing evaluation,
   policy, and an existing grant while tapping the *genuine* Accept button.
   Mitigations to choose from when automation policy ships (auto-accept /
   auto-decline, `OfferAutomationConfig` — currently dormant): widen the pin
   to the whole rule for automation-feeding intents, plausibility bounds on
   parsed fields, and/or per-window rate caps beyond the per-fire throttle.
2. **Multi-step decline** (#110 Stage 2c): today DECLINE_OFFER taps the
   initial button and leaves the platform's confirm dialog to the user. A full
   auto-decline is a staged plan over *intents* (offer screen → confirm
   screen), each step aimed by that screen's own bound target, with in-flight
   state + timeout + abort-to-manual.
3. **Asset auto-grant** — keep for the alpha? (recommend yes.)
4. **Persist explicit denials** vs treat absence as deny (recommend persist).
5. **Revocation immediacy** — reactive grant flow should suppress immediately
   (recommend yes).

## How it composes with the audit findings

- **#417** — this design *is* the real gate's shape; what remains is the grant
  store + the lookup at the `PerformRuleAction` seam.
- **#416 (signatures)** — orthogonal and complementary: signatures prove a
  bundle is *authentic*; capabilities + verification constrain what an
  authentic-but-untrusted bundle may *do*. A signed malicious fork still can't
  aim a tap at anything that doesn't look like the consented action's control,
  inside the platform's own package, after the app itself decided to act.
- **#419 (sensitive rules survive replacement)** — recognition-layer, not
  actuation; same "remote bundles are untrusted" posture motivates both.

## History (why this shape)

- **f9193c7 (original):** keyed consent to the *resolved node* —
  `sha256(ruleId|verb|viewId|text)`. Right instinct (pin what the button
  actually IS), fatally incoherent for load-time consent: the resolved node
  doesn't exist at load, and live-node signatures churn with third-party UI
  (re-prompt fatigue).
- **51c58e1 (reconciliation):** moved to the binding-definition key — load-time
  enumerable and churn-stable, but discarded the runtime half instead of
  relocating it, and its "strictly stronger" claim was wrong: definition pins
  catch rule-side repoints, resolved-node checks catch world-side reshuffles.
  Each alone is insufficient.
- **#425 (this design):** keeps the definition key for the static half and
  restores the runtime half as app-authored tap-time verification — stronger
  than the original's trust-on-first-use signature because it's authored
  per-action, works on first fire, and never re-prompts (a benign label change
  fails closed to manual instead of either wrong-tapping or nagging). Also
  removed actuation from the rule vocabulary entirely, which the original kept.
