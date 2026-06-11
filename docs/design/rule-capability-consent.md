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
capabilityHash = sha256("$ruleId|${verb.wire}|${target ?: ""}")
```

This is the load-bearing security property. The grant is pinned to *what the
action does*. If a remote update to a **trusted rule id** changes its click
target — or adds a new click — the hash changes, the old grant no longer
covers it, and it re-enters the pending list for re-approval. Approving a
benign `auto_decline` today cannot be silently escalated by a malicious CDN
update to the same id tomorrow.

### Target signature granularity (a real decision)

For `CLICK`, the `RequestedEffect.targetRef: NodeRef` carries `viewIdSuffix`,
`text`, `classNameHint`, `pathFingerprint`, `bounds`. Two options:

- **Semantic (recommended):** `target = "${viewIdSuffix}|${text}"` (or a
  normalized subset). Stable across third-party UI reflows; re-prompts only when
  the rule genuinely retargets. Slightly coarser — "tap the Accept button"
  rather than "tap the button at this exact tree path."
- **Structural:** include `pathFingerprint`. Maximally precise but re-prompts on
  any third-party redesign (annoying, and trains users to click-through
  consent — a security anti-pattern).

Recommend semantic; document that bounds are never part of the key (they shift
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
