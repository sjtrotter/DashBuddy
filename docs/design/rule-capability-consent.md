# Design: action targets, capabilities + load-time consent

**Status:** layers 1–3 and the capability refit implemented by #425 (2026-06-11);
the grant store + execution gate implemented by #417 (2026-06-12): asset
auto-grant on load, AUTOMATION fires gated by (sourceRuleId, action) →
all-enumerated-keys-granted lookup, USER fires are their own consent, and the
OS tier check is real (live service handle / runtime permission). The consent
UI is #422 PR 3. Supersedes the original draft of this doc (f9193c7,
reconciled 51c58e1) and the closed #85 ("GigPlatform interface") — see
*History* at the bottom.

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
the unit of user consent (#1167). Every layout of that action shares one grant:

```
RuleCapability(
    ruleId: String,          // e.g. "doordash.screen.offer_popup"
    action: RuleAction,      // ACCEPT_OFFER | DECLINE_OFFER | CONFIRM_DECLINE | EXPAND_EARNINGS
    targetBindName: String,  // display only
    key: String,             // the grant key — see below
    source: String,          // "asset:doordash.json" | "cdn:<url>" | "fork:<id>"
    layoutCount: Int = 1,    // distinct definitions covered; display only
)

key = sha256( canonicalJson({
    "rule":   ruleId,
    "action": action.wire,
    "bind":   action.targetBindName,
    "defs":   [<sorted canonical binding definitions across every branch>]
}) )
```

Enumeration (`RuleCompiler.enumerateCapabilities`) collects rule-level and
branch-level action bindings against the `RuleAction` registry. Each definition
is recursively key-sorted canonical JSON; a binding with no recorded definition
is not consentable at all (it enumerates no capability); `compileBindBlock` always
records one, so this is a defensive boundary. `defs` contains the **distinct canonical strings**,
sorted lexicographically, rather than raw JSON objects. An inherited duplicate
counts once. `layoutCount` is this set's size, so the UI can disclose how many card
layouts one grant covers without making the count a separate key input.

The key pins the entire set of binding definitions:

- Repointing a predicate in **any** branch changes the key: an old grant cannot
  cover a rule update that silently aims Decline at the Accept button.
- Adding a distinct layout changes the key, causing **one re-prompt** for the
  (rule, action) pair.
- Reordering branches leaves the key unchanged. Reordering object keys or
  inheriting another copy of an existing definition also leaves it unchanged.

Residual (Astra review, 2026-09-30): the key pins the SET of binding definitions,
not each definition's association with its branch's `require`/`reject`. A remote
update can therefore swap two existing definitions between branches, or change
a branch's recognition conditions, without changing the key — the static half
never pinned recognition context (pre-#1167 per-definition keys had the same
property). The DYNAMIC half is the control: fire-time package scope + label
verification; `expand_earnings` is package-scoped only (icon-only arrow) and is
the exposed case. Pinning branch conditions was REJECTED: it would re-prompt on
every recognition anchor tweak (re-prompt fatigue trains click-through).

A screen rule **must declare `enables`** whenever a rule-level or branch binding
names an action target, for example `"enables": ["accept_offer", "decline_offer"]`.
The declaration is a JSON array of distinct, known `RuleAction.wire` strings at
the rule's top level; branches never declare it. The compiler rejects a
**bound-but-undeclared** action and a **declared-but-unbound** action. No action
targets and no declaration is valid (both sets are empty). This declares the
app-owned actions available for consent; rules still never declare actuation.

**No key threading.** The fire-time gate looks grants up from the enumeration,
keyed by the `sourceRuleId` + `action` carried by `PerformRuleAction`. Consent
uses authoritative state rather than keys threaded through effect pipelines.

Store schema v2 (#1167) cleared every pre-v2 grant and denial; the prompt re-collected.

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

### Source policy (superseded by #843 — no auto-grant)

**As of #843 there is NO auto-grant, from any source.** `reconcile()` only
publishes the enumeration; every capability — bundled OR downloaded — lands
**undecided** until the user explicitly opts in. Per Google Play policy each
automation is consented to individually. Consent is collected by the
**prompt** (`ConsentPromptPage` in the `FrontDoorSheet`, the app's front door — same rhythm as the
a11y/notification permission prompts), and reviewed/revoked in the settings
record. A one-shot schema migration clears any pre-#843 auto-granted keys on
upgrade (denials preserved) so the prompt re-collects honest consent.

`ASSET_SOURCE_PREFIX` survives as display-only provenance: bundled = "built-in"
(covered by the APK signature), downloaded = a future CDN/fork source (#192),
which additionally gates on signature verification (#416) before it may even be
enumerated. Neither is granted without a user act. A rule can recognize screens
immediately but cannot aim a tap without an explicit, content-pinned grant.

### Feature consents (#1151) — distinct from capability grants

Some consents switch a whole FEATURE on rather than authorizing one tap: the
wide accessibility event receipt (#1151) today, the UNKNOWN-screen census
(#1138 M3) later. They do NOT go through `RuleCapabilityGrants`. Each is:

- **one value, one owner** — e.g. `EventReceiptPreferences` (`:domain`
  contract, DataStore-backed repository in `:core:data`); every reader
  derives from it, no second copy;
- **opt-in** — the default (UNDECIDED) behaves as declined; the pre-load value
  is the same fail-closed default;
- **a durable decline** — "Don't allow" persists;
- **debug requires, release offers; copy differs by source-set override, same key** (#1151 RR1):
  `event_receipt_requirement_note` is "Optional…" in `:feature:settings` `src/main/res` and
  "Required in this debug build…" in `src/debug/res`; it is rendered on the chain's step AND on
  the Automation & Consent switch row. The disclosure body stays one shared string;
- **asked BEFORE the accessibility grant** (dev re-sequencing, 2026-09-30): the
  event-receipt consent is the FIRST step of the permission chain
  (`PermissionsBottomSheet` / `PermissionsViewModel`, the "Screen events" card —
  the disclosure with Allow / Don't allow, no "Not now"), so the service is
  never enabled — and never recognizes a screen — before the dasher decided.
  The pure `buildPermissionsUiState` withholds the accessibility step until a
  decision (`accessibilityOffered`); a release decline proceeds to the grant
  (filtered footprint), a DEBUG decline never gets the grant (its shell is
  replaced by `DebugEventReceiptShell`). Until the consent is READ the queue is
  empty and nothing counts as all-granted, so no OS card ever leads the
  Screen-events step (review TT4); the Dashboard opens the sheet from this ONE
  projection (`PermissionsViewModel`). An undecided consent opens the chain even
  when every OS permission is granted. It is **recorded** on the Automation & Consent screen (a switch that
  writes through the same owner — never a second gate);
- **enforced in one place** — for event receipt, `AccessibilityListener`
  applying `ServiceInfoPolicy` to `serviceInfo.packageNames` (and, on a DEBUG
  build that declined, `disableSelf()` once — `ServiceInfoPolicy.shouldDisableSelf`
  — so recognition and the HUD stop even if the service had been granted
  before; a release build never disables itself).

The Dashboard's front door (`FrontDoorHost` on the shared `FrontDoorSheet`)
hosts only the per-capability prompt (#843); "Not now" closes it for this
foreground, anchored on a FOREGROUND GENERATION held by the activity-scoped
`FrontDoorViewModel` (bumped by `MainActivity.onStop` unless changing
configurations), so a deferral survives rotation, navigation and re-entry. It
has ONE "Not now" string (`consent_front_door_not_now`).

The disclosure copy is ONE string (`event_receipt_body`, resolved by
`eventReceiptDisclosure()` in `:feature:settings`, naming the screen through the
one `event_receipt_settings_path` string) rendered by the chain's step, the
Settings switch and the debug block, in the Play prominent-disclosure shape so
#1138 M3 can reuse it. It carries no state-dependent sentence, and states the
full scope: events of every subscribed type from every app; only enabled
delivery-app windows are inspected or captured; other windows are identified by
app name only; nothing from them is stored — except that offer screenshots,
when separately turned on, capture the whole screen whatever app is behind the
offer.

A DEBUG build treats a declined event receipt as blocking at the SHELL:
`MainActivity` renders `DebugEventReceiptShell` instead of the NavHost while
`DEBUG && DECLINED` (the backstop a dasher sees on reopening a declined debug
build — its body says the service has been turned off), so every destination
and deep link is unreachable; the one way forward is the Automation & Consent
screen rendered inside the shell (turning the switch on unblocks live and the
chain then re-requests the accessibility grant), plus Exit. While the consent is
unread a DEBUG shell shows a neutral loading gate (no NavHost, no deep-link
delivery), offering a notice + Exit after 3 s. A pending deep link is parked in
`MainShellViewModel`'s `SavedStateHandle` and cleared only after delivery. A
release decline keeps the app working with the topology path off.

The consent is ONE nullable `StateFlow`; `null` means ONLY "not read yet". One
collector on the application scope reads the store (review PP1): a failed read
is retried with bounded backoff (5 × 1 s·attempt; a WARN per attempt, ERROR only
when exhausted) and, exhausted, settles on the value already known this process,
else a USABLE UNDECIDED. The collector is the ONLY writer of the value (review
SS1): `set()` only writes the store, on the APPLICATION scope (a closing screen
can never cancel it half-way), and the collector publishes the decision when the
store emits; a failed write is logged and reported as `false`, never thrown, the
value untouched. There is ONE collector for the repository's lifetime: after its
retries are exhausted it waits for a CONFLATED "read again" poke that every
successful write sends, so a write that lands mid-exhaustion is still read back
and two collectors can never exist (review UU1/UU2). Enforcement
treats `null` as UNDECIDED and applies once per distinct policy OUTPUT (`isWide`),
so one apply and one INFO line per connect; a failed apply is RETRIED with a
doubling backoff capped at 30 s until it lands (`ServiceInfoPolicy.retryDelayMs`,
`collectLatest` — a newer consent cancels the retry), so a failed narrowing can
never leave the subscription wide (SS2). A debug build that connects with a
known decline disables itself BEFORE registering the source or reporting the
locale boundary (TT1). An apply never takes sensing down: the
service scope has a `CoroutineExceptionHandler` and the apply catches
everything (one log line; the manifest's filtered footprint stays); a null
`serviceInfo` (no connection) is one WARN and `onServiceConnected` re-applies.
On Android 11 the runtime package filter may not clear; the listener WARNs once
and the switch carries a caveat (the rule's one owner is
`EventReceiptConsent.isWideReceiptReliable`). The on/off → decision rule has one
owner, `EventReceiptConsent.of(allowed)`. `ServiceInfoPolicy` refuses an EMPTY
package registry (the framework treats an empty `packageNames` like `null`), and
a unit test pins the manifest's `android:packageNames` equal to
`Platform.watchedPackages`.

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
3. **Asset auto-grant** — RESOLVED by #843: removed. Bundled sources are
   prompted, not auto-granted (Google Play per-automation consent). The
   original alpha recommendation ("keep") is superseded.
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
