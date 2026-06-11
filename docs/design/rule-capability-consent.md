# Design: per-rule action capabilities + load-time consent

**Status:** proposal (2026-06-11). Concrete shape for #417 (make the permission
gate real) and a safety prerequisite for #192 (matchers split — remote rules).
Prompted by: "dynamically bundle the rule ACTIONS into a user-permission list at
load time, if not already granted for that unique rule."

## The problem it solves

A rule can declare **actuating effects** — today `CLICK` (taps the third-party
app's UI), `SCREENSHOT`, `SPEAK`; tomorrow likely `SWIPE`/`SCROLL`/`SET_TEXT`/
global actions. Right now `SideEffectEngine.isPermissionGranted(tier)` returns
`true` for everything, so any rule-declared action fires unconditionally. For
bundled rules that's a deliberate single-user choice; once rules arrive from a
forkable CDN (#192), it's **arbitrary UI actuation from untrusted input**.

The blunt fix (#417) is to make the tier check real — "is the accessibility
service enabled." But that's all-or-nothing: enabling the service to get
recognition would also bless *every* rule's clicks. The better model, and what
this design proposes, is **capability consent keyed on the (rule, action) pair**:
the user approves "rule X may tap the *Accept* button," not "rules may click."

## Core model

A **RuleCapability** is the unit of consent — one actuating action a specific
rule wants to perform:

```
RuleCapability(
    ruleId: String,        // e.g. "doordash.offer.auto_decline"
    verb: EffectVerb,      // CLICK, SWIPE, SCREENSHOT, SPEAK, …
    target: String?,       // semantic target signature for UI-targeting verbs
    source: String,        // "asset:doordash.json" | "cdn:<url>" | "fork:<id>"
)
```

**Grant key = a content hash**, not the rule id alone:

```
capabilityKey = sha256( canonicalJson({
    "rule": ruleId,
    "verb": verb.wire,
    "bind": targetBindName,        // the rule's bind name for the target, e.g. "declineButton"
    "def":  <the binding DEFINITION that bind name resolves to>
}) )
```

This is the load-bearing security property, and the key is pinned to the
binding **definition** — the matching predicate that *selects* the node the
rule will act on. Implementation note (this corrects an earlier draft of this
doc that hashed a resolved `viewId|text` target): click targets are **dynamic**
— a `click` effect declares a *bind name* (`"$declineButton"`) and the concrete
`NodeRef` is resolved against the live UI tree only at match time, so the
resolved node does not exist at load and cannot be the consent key. The binding
definition *is* known at load (the `bind` block survives compile), so that is
what we pin to — which is strictly stronger than `viewId|text`: if a remote
update to a **trusted rule id** keeps the bind name but repoints
`declineButton`'s predicate at the **Accept** button, the definition changes →
the key changes → it re-enters consent. Approving a benign `auto_decline` today
cannot be silently escalated by a malicious update to the same id tomorrow.

Two robustness details, both load-bearing for a *security* key and both tested:

- **Structurally-unambiguous input.** The key input is a *canonical JSON object*
  (recursively sorted keys), not delimiter-joined fields. Rule ids and bind
  names are arbitrary JSON strings with no charset constraint, so any in-band
  delimiter could let distinct tuples collide; JSON string escaping makes the
  field boundaries exact.
- **Canonical (sorted-key) serialization.** Reordering a binding's JSON keys
  must NOT change the key — otherwise an innocuous reformat re-prompts the user,
  training click-through (a security anti-pattern). `canonicalJson` sorts keys
  recursively so semantically-identical definitions hash identically.

The key is computed **once at compile** (`RuleCompiler.assignCapabilityKeys`)
and carried unchanged onto `RequestedEffect.capabilityKey`, so load-time
enumeration and the runtime consent gate compute the same value by construction.
Bounds and other live-node attributes are never part of the key (they shift
constantly).

## Lifecycle

1. **Enumerate at load.** Generalize the existing
   `RuleCompiler.enumeratePermissions(rules): Set<PermissionTier>` into
   `enumerateCapabilities(rules): List<RuleCapability>` — walk every branch's
   effects, keep the actuating ones (a defined `EffectVerb.isActuating` set:
   tier ∈ {ACCESSIBILITY, AUDIO} and not app-internal). App-internal effects
   (`BUBBLE`, `LOG`, `EVALUATE_OFFER`, odometer, timers) never need consent.
2. **Partition against the grant store.** New persisted set of granted
   `capabilityHash`es (`RuleCapabilityDataSource` in `:core:datastore` behind a
   Hilt qualifier → `RuleCapabilityRepository` in `:core:data` exposing
   `grantedHashes: StateFlow<Set<String>>`, `grant(hash)`, `revoke(hash)` —
   reactive, per the shared-StateFlow pattern from #356).
   - `granted` → action allowed.
   - `pending` → **recognition still runs; the actuating effect is suppressed
     (fail closed) until granted.** Populates a user-facing list.
3. **Consent surface.** A review screen (settings + a prompt when *new* pending
   capabilities appear after a ruleset update) rendering each in human terms —
   "Rule `…auto_decline` wants to **tap** *Decline offer*" — with allow / deny.
4. **Execution gate** (this is the real #417 fix). The engine fires an actuating
   `RequestedEffect` iff **both**:
   - the OS-level tier is granted (accessibility service enabled / audio
     allowed), AND
   - `capabilityHash(effect) ∈ grantedHashes`.
   Either missing → log + skip (fail closed). Non-actuating effects bypass the
   capability check entirely.

## Bundled vs remote: keep the alpha frictionless, make the split safe

`loadSingle`/`load` already thread a `source`. Policy:

- **Asset source** (the bundled rules the developer ships) → **auto-grant** on
  load. They're trusted; no consent friction in the single-user build.
- **CDN / fork source** (#192) → **never auto-grant**; every actuating
  capability is pending until the user approves it. This is what makes "rules
  delivered over CDN" safe *by construction* — a downloaded rule can recognize
  screens immediately but cannot touch the UI without an explicit, content-
  pinned grant.

So this design is shippable in two stages: the enumeration + grant store + gate
land now (with asset auto-grant, so behavior is unchanged for bundled rules but
the gate is finally real), and the consent UI + non-auto-grant remote policy
land with the matchers work.

## How it composes with the audit findings

- **#417** — this *is* the real gate; `isPermissionGranted` becomes
  `isCapabilityGranted(ruleId, effect)` = OS tier AND content-hash grant.
- **#416 (signatures)** — orthogonal and complementary: signatures prove the
  bundle is *authentic* (from the configured source); capabilities constrain
  what an authentic-but-untrusted bundle may *do*. Defense in depth — you want
  both. A signed malicious fork still can't actuate without per-action consent.
- **#419 (sensitive rules survive replacement)** — capabilities don't replace
  the sensitive-screen block (that's recognition-layer, not actuation), but the
  same "remote bundles are untrusted" posture motivates both.

## Open decisions for the developer

1. Target granularity: semantic vs structural (recommend semantic).
2. Asset auto-grant: yes for the single-user alpha? (recommend yes.)
3. Consent UX: load-time modal vs a passive "N rules want new permissions" badge
   that opens a review screen (recommend the badge — less interrupt, and a modal
   on every CDN update trains click-through).
4. Denied vs merely-not-granted: persist explicit denials (so a re-load doesn't
   re-surface a rejected capability) vs treat absence as deny and let re-loads
   re-prompt. (Recommend persisting denials.)
5. Revocation: a granted capability revoked in settings should suppress the
   effect immediately (reactive grant flow) — confirm that's desired vs
   next-load.
