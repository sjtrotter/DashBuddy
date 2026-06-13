# ADR-0001: Matcher / Classifier Rule Format for OTA Updates

**Status:** Accepted (Revised)
**Issue:** #87 (sub-RFC of Epic #192)
**Date:** 2026-04-28
**Revision:** v2 — DSL redesign after exhaustive review of all 31 matchers, 17 parsers, and
utility functions. Introduces 5-phase evaluation pipeline, named bindings, first-class
rejection, validation phase, parse sub-language, engine-owned TransformRegistry, and platform
envelope header with pipeline declarations.

---

> **Implementation status (2026-06-13, #440).** The **rule format / engine** half of this ADR
> is implemented and shipping; the **distribution** half is aspirational. Read the spec below
> with this table in mind:
>
> | Area | Status |
> |---|---|
> | Compiled rule format, 5-phase evaluation, named bindings, reject entries, `on` modifier, parse sub-language, engine-owned transforms | **Implemented** — see `RuleCompiler` / `Ruleset` / `JsonRuleInterpreter`, `docs/rules.schema.json` |
> | Platform envelope header (`platform_id` / `format_version` / `pipelines`) | **Implemented** (in the bundled `assets/rules/*.json`) |
> | JSON5 authoring → canonical JSON tooling | **Future** — rules are hand-authored as JSON today; no JSON5 toolchain |
> | Signing / integrity verification before compile | **Future** — hard prerequisite for any remote source (#416) |
> | CDN / OTA delivery, dual-running, forkable sources | **Future** — the #192 matchers split; rules are bundled in-APK today (`assets/rules/`) |
>
> The DSL↔schema discrepancies are tracked separately in #241.

## Context

Screen matchers, click classifiers, and notification classifiers are currently compiled into the
APK. Any DoorDash UI change that breaks a recognizer requires an app update, a Play Store review
cycle (up to 7 days for accessibility apps), and user action. During that window the dasher fleet
is blind — the offer evaluator stops scoring and the bubble HUD goes stale.

The goal is an OTA rule format that lets us push matcher and classifier fixes without a Play Store
release, while remaining:

- **Config, not code.** Pure declarative data — no embedded scripts, no `eval`, no dynamic class
  loading. The interpreter lives in the app; only data crosses the wire. This keeps us clearly on
  the right side of Google Play's dynamic code loading policy.
- **Human-writable.** Community members who are not Kotlin developers must be able to read and
  write rules. Common operations need human-friendly vocabulary, not raw AST JSON.
- **Fast on the hot path.** Accessibility events fire on every UI change. A JSON parse per event
  is unacceptable. Rules are compiled once at startup into lambda trees; the hot path is pure
  lambda invocation.
- **Auditable and signed.** The published rule file is signed with an Ed25519 developer key.
  Custom URLs are allowed (user sovereignty), but unsigned sources show a warning in the UI.
- **Platform-agnostic.** The interpreter is not DoorDash-specific. The rule file header declares
  what platform, packages, and pipelines it targets. A future `uber.driver` or
  `instacart.shopper` rule set uses the same interpreter with a different rule file — no app
  code changes.

---

## Decision

**Rule format:** JSON5 for human authoring; converted to canonical signed JSON for distribution.
YAML was considered and rejected: the Norway problem (boolean coercion of country codes),
indentation sensitivity, and multi-document footguns all make it dangerous for community PRs.
JSON5 gives us comments and trailing commas without those risks.

**Evaluation pipeline:** Every screen rule is evaluated in five phases:
**bind -> reject -> require -> parse -> validate**. Bind finds and names nodes for later
reference. Reject is the fail-fast path — any rejection fires and the rule returns null
immediately. Require is the positive-match gate. Parse extracts typed fields. Validate
(optional) checks assertions on parsed values to confirm the match — e.g., DeliverySummary
must parse out the pay breakdown before it can check whether the amounts sum correctly. If
validate fails, the rule can fall through to the next branch or next rule. This explicit
pipeline replaces the prior `guards`/`if` model to make fail-fast semantics first-class and
support bound-node rejection and value-based match confirmation.

**Match/parse split:** The DSL has two distinct sub-languages. The *match vocabulary*
(predicates, bindings, rejection, requirements) determines IF a rule fires. The *parse
vocabulary* (find, read, transform, each, navigate, join, coalesce) determines WHAT data is
extracted. Predicates appear in `reject`/`require`; parse primitives appear in `parse`. The
vocabularies are cleanly separated.

**TransformRegistry:** Transform functions are owned by the rule engine, not external utility
classes. The engine contains a closed, enumerated `TransformRegistry` mapping DSL names to
Kotlin implementations. Rule files reference transforms by name; the registry is the
single source of truth for what's available. New transforms require an app update. This
makes the engine self-contained, its vocabulary discoverable, and prevents rule files from
invoking arbitrary code.

**Interpreter:** Compile-once approach. At startup (or when new rules are fetched), the JSON is
parsed once into an AST, then compiled into lambda trees stored in memory. Every hot-path
invocation is a pure lambda call — no parsing, no reflection.

**Dual-running during development:** While the Kotlin matchers/classifiers coexist with the
interpreter, debug builds run both and log any disagreement.

**Parsers in the DSL:** Every parser is fully expressible in the DSL. There are no native
fallbacks — any fix must be shippable OTA without a Play Store release.

---

## Rule File Structure

```json5
// rules.doordash.json5 — human-authored source
// Tooled to produce rules.doordash.json (canonical JSON) for distribution
{
  format_version: 2,           // integer; bumped on breaking DSL changes
  engine_version: "1.0.0",    // minimum TransformRegistry version required

  // Platform envelope — declares what this rule set targets and what it consumes
  platform: {
    id: "doordash.driver",
    display_name: "DoorDash Driver",
    packages: ["com.doordash.driverapp"],
    pipelines: {
      screen:       { input: "UiNode" },
      click:        { input: "UiNode" },
      notification: { input: "RawNotificationData" },
    },
  },

  screens: [ /* ScreenRule[] */ ],
  clicks:  [ /* ClickRule[]  */ ],
  notifications: [ /* NotificationRule[] */ ],
}
```

The **platform envelope** replaces the per-rule `platform_id` field. One rule file = one
platform. The interpreter loads one rule file per configured platform.

`packages` tells the accessibility service which Android package names to filter events from.

`pipelines` declares the data contract between the rule set and the app — which pipeline
types this rule set provides rules for, and what data model it expects to receive for each.
The app owns all wiring (event sources, permissions, pipeline construction); the rule file
just states what it needs. A future `uber.driver` rule set might declare only `screen` and
`click` (no notification listener). A future version could add a
`location: { input: "GpsEvent" }` pipeline — the interpreter doesn't change, only the rule
file. The app validates that it can satisfy every declared pipeline before loading the rules;
unsatisfied pipelines cause a graceful fallback to bundled defaults.

`engine_version` is a semver string. The app's `TransformRegistry` exposes its own version.
If the rule file's `engine_version` exceeds the registry's version, the file is rejected and
bundled defaults are used.

---

## Evaluation Pipeline

Every screen rule is evaluated in five phases. If any phase fails, evaluation stops and the
next rule (or next branch within a multi-branch rule) is tried.

```
                      +-----------+
                      |   bind    |  Find nodes, assign $names.
                      |           |  Mandatory binds that miss → skip rule.
                      +-----+-----+
                            |
                      +-----v-----+
                      |  reject   |  Fail-fast negative checks.
                      |           |  ANY rejection fires → skip rule.
                      +-----+-----+
                            |
                      +-----v-----+
                      |  require  |  Positive match conditions.
                      |           |  ALL must pass → rule matches.
                      +-----+-----+
                            |
                      +-----v-----+
                      |   parse   |  Extract typed fields.
                      +-----+-----+
                            |
                      +-----v-----+
                      | validate  |  (Optional) Assert conditions on
                      |           |  parsed values. Fail → next branch
                      |           |  or next rule.
                      +-----+-----+
                            |
                         output
```

**Why this order:**

- **bind before reject** — PickupNavigationMatcher needs to find `bottom_sheet_task_title`
  before it can reject based on the node's text content. Bind must precede reject so that
  bound-node predicates are available.
- **reject before require** — fail-fast. If the tree contains a disqualifying signal
  ("Return to dash", "Earnings Mode Switcher"), we skip immediately without evaluating the
  (often more expensive) positive match conditions.
- **parse after require** — field extraction only runs on confirmed matches.
- **validate after parse** — computed value checks need parsed field values. DeliverySummary
  must parse out `doorDashPay`, `customerTips`, and `totalPay` before it can check whether
  the breakdown sums correctly. If validate fails, the match is "undone" — try the next branch
  or next rule.

---

## Screen Rules

A screen rule identifies which `Screen` enum value a given `UiNode` tree represents. Rules
are evaluated in ascending `priority` order; the first match wins.

### Shape: single-target

```json5
{
  id: "doordash.screen.pickup_navigation",
  priority: 10,
  overrideable: true,
  target: "PICKUP_NAVIGATION",

  // Phase 1: bind — find nodes and name them for later phases
  bind: {
    taskTitle: { find: { id: "bottom_sheet_task_title" } },
    arriveBy:  { find: { id: "arrive_by_text" }, optional: true },
  },

  // Phase 2: reject — fail-fast negative checks (any fires → skip rule)
  reject: [
    { on: "$taskTitle", hasTextContaining: "Deliver to" },
    { on: "$arriveBy",  hasTextContaining: "Deliver by" },
  ],

  // Phase 3: require — positive match conditions (all must pass)
  require: { bound: "$taskTitle" },  // taskTitle must exist (already true since mandatory)

  // Phase 4: parse — extract fields
  parse: { /* see Parse Sub-Language section */ },

  // Phase 5: validate — (optional) assert conditions on parsed values
  // validate: [ { assert: "...", args: {...}, onFail: "skip" } ],
}
```

**Bind semantics:** Each bind entry runs `findNode` on the tree using the given predicate.
By default bindings are mandatory — if the node is not found, the rule does not match
(skipped before reject even runs). Add `optional: true` for nodes that may or may not exist;
optional bindings resolve to null. Bound names are referenced as `$name` in reject, require,
and parse.

### Shape: multi-branch

For matchers that can return one of several screens based on tree content, use `branches`
instead of `target`. Each branch has its own reject/require/parse/validate. The first branch
whose full pipeline succeeds (including validate) wins.

```json5
{
  id: "doordash.screen.offer",
  priority: 20,
  overrideable: true,
  branches: [
    {
      target: "OFFER_POPUP_CONFIRM_DECLINE",
      require: { exists: { hasTextContaining: "sure you want to decline" } },
    },
    {
      target: "OFFER_POPUP",
      reject: [
        { exists: { id: "progress_bar" } },
      ],
      require: { all: [
        { exists: { hasText: "Decline" } },
        { exists: { any: [{ hasText: "Accept" }, { hasText: "Add to route" }] } },
      ]},
      parse: { /* ... */ },
    },
  ],
}
```

Rule-level `bind` is shared across all branches. Branch-level `bind` is scoped to that branch.

### Reject entries

A reject entry can be either:

1. **Tree-level predicate** (no `on`): searches the entire tree.
   `{ exists: { hasText: "Return to dash" } }` — "if any node has this text, reject."
   `{ allTextContains: "looking for offers" }` — "if joined text contains this, reject."

2. **Bound-node predicate** (with `on`): checks a specific bound node.
   `{ on: "$taskTitle", hasTextContaining: "Deliver to" }` — "if $taskTitle's text contains
   this, reject."

Reject entries are OR — if **any** entry fires, the rule is rejected. Each entry can use the
full predicate vocabulary including `all`/`any`/`not` combinators for complex conditions.

If a bound-node predicate references an optional binding that resolved to null, the predicate
evaluates to false (no rejection — can't reject on a node that doesn't exist).

### The `on` modifier in require

The `on: "$name"` modifier also works in `require` — use it to assert conditions on a bound
node's content as a positive match requirement:

```json5
// DropoffNavigationMatcher: REQUIRE that task title says "Deliver to"
// (the inverse of PickupNavigationMatcher's reject)
bind: {
  taskTitle: { find: { id: "bottom_sheet_task_title" } },
},
require: { any: [
  { on: "$taskTitle", hasTextContaining: "Deliver to" },
  { all: [
    { on: "$taskTitle", hasTextContaining: "Heading to" },
    { exists: { all: [{ id: "arrive_by_text" }, { hasTextContaining: "Deliver by" }] } },
  ]},
]},
```

In reject, `on` fires a NEGATIVE check (if true → skip). In require, `on` fires a POSITIVE
check (must be true to match).

---

## Predicate Vocabulary — Tree-Level

These predicates operate on the full `UiNode` tree. Used in `reject` (without `on`) and
`require`.

| Predicate                           | Meaning                                               | Kotlin equivalent                                 |
|-------------------------------------|-------------------------------------------------------|---------------------------------------------------|
| `{ exists: <nodePred> }`            | Some node in tree satisfies predicate                 | `tree.findNode { ... } != null`                   |
| `{ notExists: <nodePred> }`         | No node in tree satisfies predicate                   | `tree.findNode { ... } == null`                   |
| `{ allTextContains: "s" }`          | Joined lowercase text contains `s` (case-insensitive) | `tree.allTextLowerJoined.contains(s.lowercase())` |
| `{ allTextContainsAll: ["a","b"] }` | All strings present in joined text                    | AND of `allTextContains`                          |
| `{ allTextContainsAny: ["a","b"] }` | Any string present in joined text                     | OR of `allTextContains`                           |
| `{ bound: "$name" }`                | Named binding resolved to a non-null node             | `bindings["name"] != null`                        |
| `{ all: [ pred, ... ] }`            | All child predicates must pass                        | logical AND                                       |
| `{ any: [ pred, ... ] }`            | At least one child predicate must pass                | logical OR                                        |
| `{ not: pred }`                     | Negate                                                | logical NOT                                       |

---

## Predicate Vocabulary — Node-Level

These predicates operate on a single `UiNode`. Used inside `exists`/`notExists`, in reject
entries with `on`, or combined with `all`/`any` at the node level.

| Predicate                                | Meaning                                                                  | Kotlin equivalent                   |
|------------------------------------------|--------------------------------------------------------------------------|-------------------------------------|
| `{ id: "s" }`                            | Node ID is `s` (suffix match — Android fully-qualifies IDs with package) | `.endsWith("s")`                    |
| `{ hasIdExact: "s" }`                    | Full `viewIdResourceName` equals `s` (rare — includes package prefix)    | `== "s"`                            |
| `{ hasIdContaining: "s" }`               | `viewIdResourceName` contains `s`                                        | `.contains("s")`                    |
| `{ hasNoId }`                            | `viewIdResourceName` is null or blank                                    | null/blank check                    |
| `{ hasText: "s" }`                       | `text` equals `s` **(case-insensitive)**                                 | `.equals("s", ignoreCase=true)`     |
| `{ hasTextCaseSensitive: "s" }`          | `text` exactly equals `s`                                                | `== "s"`                            |
| `{ hasTextContaining: "s" }`             | `text` contains `s` (case-insensitive)                                   | `.contains("s", ignoreCase=true)`   |
| `{ hasTextStartsWith: "s" }`             | `text` starts with `s` (case-insensitive)                                | `.startsWith("s", ignoreCase=true)` |
| `{ hasTextMatchesRegex: "p" }`           | `text` matches regex pattern `p`                                         | `Regex(p).containsMatchIn(text)`    |
| `{ hasDesc: "s" }`                       | `contentDescription` equals `s` (case-insensitive)                       | `.equals("s", ignoreCase=true)`     |
| `{ hasDescContaining: "s" }`             | `contentDescription` contains `s` (case-insensitive)                     | `.contains("s", ignoreCase=true)`   |
| `{ hasStateDescription: "s" }`           | `stateDescription` equals `s` (case-insensitive)                         | `.equals("s", ignoreCase=true)`     |
| `{ hasStateDescriptionContaining: "s" }` | `stateDescription` contains `s` (case-insensitive)                       | `.contains("s", ignoreCase=true)`   |
| `{ hasClassName: "s" }`                  | `className` exactly equals `s`                                           | `== "s"`                            |
| `{ hasClassNameEndsWith: "s" }`          | `className` ends with `s` (case-insensitive)                             | `.endsWith("s", ignoreCase=true)`   |
| `{ isClickable }`                        | node is clickable                                                        | `node.isClickable == true`          |
| `{ isEnabled }`                          | node is enabled                                                          | `node.isEnabled == true`            |
| `{ isChecked }`                          | node is in checked state                                                 | `node.isChecked == 1`               |
| `{ hasChildren }`                        | node has at least one child                                              | `node.children.isNotEmpty()`        |
| `{ isLeaf }`                             | node has no children                                                     | `node.children.isEmpty()`           |
| `{ all: [ nodePred, ... ] }`             | All node predicates must pass                                            | logical AND within a node           |
| `{ any: [ nodePred, ... ] }`             | At least one node predicate must pass                                    | logical OR within a node            |
| `{ not: nodePred }`                      | Negate a node predicate                                                  | logical NOT within a node           |

The table covers the **complete** `UiNode` data model surface. Every field exposed by
`AccessibilityNodeInfo` that DashBuddy captures is addressable via a predicate, so community
contributors do not need to read the Kotlin source to know what is available.

---

## Parse Sub-Language

The parse block extracts typed fields from a matched tree. It has its own vocabulary, distinct
from the match predicates. The match sub-language answers "does this rule fire?"; the parse
sub-language answers "what data do we extract?"

### Design principles

1. **Matchers as the first validation line.** The `require` predicate should encode as many
   screen invariants as possible. A parser only runs when the matcher has already confirmed the
   screen is structurally correct.

2. **Structural sources over positional where possible.** `find` by ID is more robust than
   `textAfterLabel` when a reliable `viewIdResourceName` exists. Use `textAfterLabel` only
   when no viewId is available and the label-value adjacency is a design invariant of the
   target platform's screen (e.g., DoorDash always renders "This dash" immediately before its
   dollar amount).

3. **The engine owns all computation.** Parse fields specify WHAT to find and HOW to transform
   it, but all actual computation is dispatched through the TransformRegistry. Rule files
   cannot define new functions.

### Field extraction primitives

Each field in the `parse` block is an extraction expression built from these primitives:

| Primitive         | Purpose                                        | Example                                                        |
|-------------------|------------------------------------------------|----------------------------------------------------------------|
| `find`            | Locate a node by predicate                     | `{ find: { id: "store_name" } }`                               |
| `findAll`         | Locate all matching nodes                      | `{ findAll: { hasTextStartsWith: "Pickup for " } }`            |
| `read`            | Read a field from the found node               | `read: "text"` / `read: "contentDescription"`                  |
| `transform`       | Apply named function(s) from TransformRegistry | `transform: "parseCurrency"` / `transform: ["trim", "sha256"]` |
| `textAfterLabel`  | Positional lookup in `allText` DFS list        | `{ textAfterLabel: "This dash", offset: 1 }`                   |
| `navigate`        | Tree-walk from current node                    | `navigate: "sibling(1)"` / `navigate: "ancestor(2)"`           |
| `each`            | Iterate and extract structured objects         | See collection extraction below                                |
| `join`            | Concatenate multiple sources                   | `{ join: [src, src], separator: ", " }`                        |
| `coalesce`        | First non-null of multiple sources             | `{ coalesce: [src1, src2] }`                                   |
| `presence`        | Boolean: does a tree predicate pass?           | `{ presence: { exists: { hasText: "..." } } }`                 |
| `conditionalEnum` | First-matching enum value                      | `{ conditionalEnum: [{ if: <pred>, then: "VALUE" }] }`         |
| `fallback`        | Default value when result is null              | `fallback: "unknown"`                                          |
| `rejectIf`        | Null out if found node matches predicate       | `rejectIf: { hasTextContaining: "instructions" }`              |

### Simple field extraction

```json5
// Find node by ID suffix, read its text, apply transform
storeName: {
find: {id: "store_name"},
read: "text",
}

// Find node by ID, read text, transform to typed value
deadline: {
find: { id: "deadline_text"},
read: "text",
transform: "parseDeadline",
}

// Hash PII
customerNameHash: {
find: { id: "customer_name"},
read: "text",
transform: "sha256",
}

// allText positional lookup — label immediately precedes value in DFS order
dashEarnings: {
textAfterLabel: "This dash",
offset: 1,
transform: "parseCurrency",
}

// Parse leading integer from "4 items" text
itemCount: {
find: {id: "items_title_v2"},
read: "text",
transform: "parseLeadingInt",
}
```

### Navigate primitive

Navigate walks the tree from a found node:

| Expression                        | Meaning                                                      |
|-----------------------------------|--------------------------------------------------------------|
| `"parent"`                        | `node.parent`                                                |
| `"ancestor(N)"`                   | `node.ancestor(N)` — walk N levels up                        |
| `"sibling(N)"`                    | `node.sibling(N)` — sibling at offset N in parent's children |
| `{ findChild: { id: "s" } }`      | First direct child matching predicate                        |
| `{ findDescendant: { id: "s" } }` | First descendant matching predicate                          |

```json5
// WaitingForOfferParser: find button, then read its child's text
waitTime: {
find: {id: "wait_time_button"},
navigate: {findChild: {id: "textView_prism_button_title"}},
read: "text",
transform: {
replace: "est. ", with: ""},
}

// TimelineViewParser: each task node's deadline is the next sibling
deadline: {
navigate: "sibling(1)",
read: "text",
transform: [{extractBefore: " \u2022 "}, "parseDeadline"],
}
```

### Coalesce (layout variant fallbacks)

Screens with multiple layout variants use `coalesce` to try sources in order, taking the
first non-null result:

```json5
// WaitingForOffer: new layout uses label/value pair; legacy uses button child
waitTimeEstimate: {
coalesce: [
// New layout — "Zone offer wait" label is immediately before the estimate in allText
{textAfterLabel: "Zone offer wait", offset: 1},
// Legacy — button with child text node
{
find: {id: "wait_time_button"},
navigate: {findChild: {id: "textView_prism_button_title"}},
read: "text",
transform: {
replace: "est. ", with: ""},
},
],
}
```

### Join (address construction)

```json5
address: {
join: {
parts: [
{find: {id: "store_address_line1"}, read: "text"
},
{
find: {
id: "store_address_line2"}, read: "text"},
],
separator: ", ",
skipNulls: true,
},
transform: "sha256",
}
```

### Presence and conditionalEnum (boolean and enum fields)

```json5
// Boolean: is a specific node present?
hasRedCard: {
  presence: { exists: { all: [{ id: "tag" }, { hasTextContaining: "Red Card" }] } },
}

// Enum: first matching condition determines value
dashType: {
  conditionalEnum: [
    { if: { exists: { hasDesc: "Time mode off" } }, then: "PER_OFFER" },
    { else: "TIME" },
  ],
}

// Status from button text
status: {
  conditionalEnum: [
    { if: { exists: { all: [{ id: "primary_action_button" }, { hasTextContaining: "Confirm" }] } }, then: "CONFIRMING" },
    { if: { exists: { all: [{ id: "primary_action_button" }, { hasTextContaining: "Continue" }] } }, then: "CONTINUING" },
    { else: "UNKNOWN" },
  ],
}
```

### RejectIf (conditional field nulling)

```json5
// PickupArrivalParser: instructions_title is sometimes "Parking instructions" — reject those
storeName: {
  find: { id: "instructions_title" },
  read: "text",
  rejectIf: { any: [{ hasTextContaining: "instructions" }, { hasTextContaining: "notes" }] },
}
```

### Collection extraction with `each`

`each` iterates over all tree nodes matching a predicate and extracts one structured object
per matched node. The result is a typed list.

`scope` optionally walks up from the matched node to a container. Sources in the `extract`
block then search within that container, not the whole tree.

```json5
// OfferParser: each display_name node is inside a per-order card 2 levels up
orders: {
  each: {
    findAll: { id: "display_name" },
    scope: { ancestor: 2 },
    exclude: { any: [{ hasText: "Customer dropoff" }, { hasText: "Business handoff" }] },
    extract: {
      storeName: { read: "text" },
      orderType: {
        conditionalEnum: [
          { if: { exists: { all: [{ id: "work_unit_type" }, { hasTextContaining: "Shop" }] } }, then: "SHOP_FOR_ITEMS" },
          { else: "PICKUP" },
        ],
      },
      itemCount: {
        find: { id: "display_name_secondary" },
        read: "text",
        transform: "parseItemCount",
        fallback: 1,
      },
      hasRedCard: {
        presence: { exists: { all: [{ id: "tag" }, { hasTextContaining: "Red Card" }] } },
      },
    },
  },
}
```

```json5
// TimelineViewParser: task chain extraction
tasks: {
  each: {
    findAll: { any: [
      { hasTextStartsWith: "Pickup for " },
      { hasTextStartsWith: "Deliver to " },
      { hasTextStartsWith: "Pickup from " },
    ]},
    extract: {
      taskType: {
        read: "text",
        transform: { extractMatchingPrefix: ["Pickup for ", "Deliver to ", "Pickup from "] },
      },
      nameHash: {
        read: "text",
        transform: [{ stripPrefixes: ["Pickup for ", "Deliver to ", "Pickup from "] }, "trim", "sha256"],
      },
      deadline: {
        navigate: "sibling(1)",
        read: "text",
        transform: [{ extractBefore: " \u2022 " }, "parseDeadline"],
      },
      storeHint: {
        navigate: "sibling(1)",
        read: "text",
        transform: { extractAfter: " \u2022 " },
      },
      isCurrent: {
        presence: { exists: { hasText: "Current task" } },
      },
    },
  },
}
```

```json5
// RatingsViewParser: pair-walking — find all title nodes, each paired with its next sibling
metrics: {
  each: {
    findAll: { id: "textView_title" },
    extract: {
      label: { read: "text" },
      value: {
        navigate: "sibling(1)",
        read: "text",
        // Only read if the sibling is actually a description node (skip section headers)
        rejectIf: { not: { id: "textView_description" } },
      },
    },
  },
}
```

### Regex group extraction

```json5
// PickupShoppingParser: extract item count from "To shop (N)" tab label
itemCount: {
  find: { hasTextMatchesRegex: "To shop \\(\\d+\\)" },
  read: "text",
  transform: { regex: "To shop \\((\\d+)\\)", group: 1, then: "toInt" },
}
```

### Bound-node references in parse

Parse fields can reference nodes from the `bind` phase via `$name`:

```json5
{
  bind: {
    taskTitle: { find: { id: "bottom_sheet_task_title" } },
  },
  parse: {
    storeName: {
      from: "$taskTitle",
      read: "text",
      transform: { stripPrefix: "Pick up from " },
    },
  },
}
```

---

## Validate (Phase 5)

`validate` is an optional top-level block that runs **after parse**. It checks assertions on
parsed field values — confirming that the extracted data is consistent before the rule commits
to its result. Validate needs parsed values to work with, which is why it comes after parse
rather than before.

If an assertion fails, the behavior depends on `onFail`:

- `"skip"` — try the next candidate. In a multi-branch rule, this means the next branch;
  if no branches remain (or the rule is single-target), it means the next rule. The engine
  knows the context — rule authors just say "skip."
- `"simple"` — accept the match but return `ScreenInfo.Simple(target)` instead of the full
  parsed result. Use when the screen identity is certain but parsed data may be incomplete.

Each branch in a multi-branch rule can have its own `validate`. If branch 1's validate fails,
branch 2 gets its turn with its own parse and validate.

```json5
// DashSummaryParser: dash total must not exceed weekly total (data quality gate)
{
  target: "DASH_SUMMARY",
  require: { /* ... */ },
  parse: {
    fields: {
      totalEarnings:  { /* ... */ },
      weeklyEarnings: { /* ... */ },
    },
  },
  validate: [
    {
      assert: "fieldsLe",
      args: { a: "totalEarnings", b: "weeklyEarnings" },
      onFail: "simple",
    },
  ],
}

// DeliverySummary EXPANDED: pay breakdown must sum to headline total.
// If validate fails, the screen may not be fully loaded — try COLLAPSED branch.
{
  branches: [
    {
      target: "DELIVERY_SUMMARY_EXPANDED",
      require: { all: [
        { exists: { id: "final_value" } },
        { allTextContainsAll: ["doordash pay", "customer tips"] },
      ]},
      parse: {
        fields: {
          totalPay:    { find: { id: "final_value" }, read: "text", transform: "parseCurrency" },
          doorDashPay: { textAfterLabel: "DoorDash pay", offset: 1, transform: "parseCurrency" },
          customerTips:{ textAfterLabel: "Customer tips", offset: 1, transform: "parseCurrency" },
        },
      },
      validate: [
        {
          assert: "sumApproxEquals",
          args: { parts: ["doorDashPay", "customerTips"], total: "totalPay", tolerance: 0.02 },
          onFail: "skip",
        },
      ],
    },
    {
      target: "DELIVERY_SUMMARY_COLLAPSED",
      require: { all: [
        { exists: { id: "final_value" } },
        { not: { allTextContains: "doordash pay" } },
      ]},
      parse: {
        fields: {
          totalPay: { find: { id: "final_value" }, read: "text", transform: "parseCurrency" },
        },
      },
      // no validate — COLLAPSED has no breakdown to check
    },
  ],
}
```

Validate assertion functions are registered in the TransformRegistry alongside transforms.
They are a closed, auditable set — new assertions require an app update.

---

## TransformRegistry

The engine owns all computation. Transform functions are registered in a
`TransformRegistry` singleton, not scattered across utility classes or parser-private methods.
This makes the engine self-contained and the vocabulary discoverable.

### Design principles

1. **Closed set.** Rule files can only invoke registered transforms. Unknown names fail at
   compile time with `RuleCompileException`.
2. **Versioned.** The registry exposes a version string. Rule files declare `engine_version`
   in their header. If the rule file's version exceeds the registry's version, the file is
   rejected.
3. **Testable.** Each transform is unit-testable in isolation through the registry API.
4. **No external dependencies.** The registry depends only on the Kotlin stdlib and
   `java.security.MessageDigest` (for SHA-256). No Android framework, no third-party libraries.

### Transform catalog

| Transform               | Input -> Output                                                   | Notes                                                   |
|-------------------------|-------------------------------------------------------------------|---------------------------------------------------------|
| `parseCurrency`         | `"$5.00"`, `"+$4.00"`, `"$7.75+ Total"` -> `Double`               | Strips `$`, `+`, `,`; takes first space-delimited token |
| `parseDistance`         | `"3.2 mi"`, `"500 ft"`, `"Additional 2.6 mi"` -> `Double` (miles) | Regex extracts number; converts ft to mi                |
| `parseItemCount`        | `"(2 items)"`, `"(3 items \u2022 4 units)"` -> `Int`              | Regex extracts leading count                            |
| `parseDeadline`         | `"Pick up by 17:39"`, `"by 6:10 PM"` -> `ParsedTime`              | Strips prefix, parses time, returns text + epoch ms     |
| `parseTime`             | `"5:30 PM"`, `"17:30"` -> `Long` (epoch ms)                       | 12h or 24h format, rolls to next day if past            |
| `parseDuration`         | `"35:00"` (MM:SS) -> `Long` (ms)                                  | Minutes:seconds to milliseconds                         |
| `parseHrMin`            | `"2 hr 15 min"` -> `Long` (ms)                                    | Hours-and-minutes to milliseconds                       |
| `parseLeadingInt`       | `"4 items"` -> `Int`                                              | Split on space, first token to Int                      |
| `parsePercent`          | `"85.7%"` -> `Double`                                             | Strip `%` suffix, parse to Double                       |
| `parseOffers`           | `"3 out of 5"` -> `{accepted: 3, total: 5}`                       | Regex extraction of numerator/denominator               |
| `sha256`                | `String` -> `String` (hex)                                        | SHA-256 hash for PII redaction                          |
| `trim`                  | strip whitespace                                                  |                                                         |
| `lowercase`             | to lowercase                                                      |                                                         |
| `toDouble`              | `String` -> `Double`                                              |                                                         |
| `toInt`                 | `String` -> `Int`                                                 |                                                         |
| `stripDeadlinePrefix`   | `"Pick up by 17:39"` -> `"17:39"`                                 | Strips known deadline prefixes                          |
| `addressBetweenAnchors` | `(allText, startAnchor, endAnchor)` -> `String`                   | Positional address extraction                           |

**Parameterized transforms** use object syntax:

| Transform               | Syntax                                              | Meaning                             |
|-------------------------|-----------------------------------------------------|-------------------------------------|
| `stripPrefix`           | `{ stripPrefix: "Pick up from " }`                  | Remove prefix if present            |
| `stripSuffix`           | `{ stripSuffix: "%" }`                              | Remove suffix if present            |
| `stripPrefixes`         | `{ stripPrefixes: ["Pickup for ", "Deliver to "] }` | Remove first matching prefix        |
| `extractMatchingPrefix` | `{ extractMatchingPrefix: ["Pickup for ", ...] }`   | Return the prefix that matched      |
| `extractBefore`         | `{ extractBefore: " \u2022 " }`                     | Substring before first delimiter    |
| `extractAfter`          | `{ extractAfter: " \u2022 " }`                      | Substring after first delimiter     |
| `replace`               | `{ replace: "est. ", with: "" }`                    | String replacement                  |
| `split`                 | `{ split: " \u2022 ", index: 0 }`                   | Split on delimiter, return Nth part |
| `regex`                 | `{ regex: "pattern", group: 1 }`                    | Regex capture group extraction      |
| `regex`                 | `{ regex: "pattern", group: 1, then: "toInt" }`     | Capture group + chained transform   |

**Transform chaining** — array syntax applies transforms left to right:

```json5
transform: ["stripDeadlinePrefix", "parseTime"]
transform: [{ stripPrefixes: ["Pickup for ", "Deliver to "] }, "trim", "sha256"]
transform: [{ replace: "est. ", with: "" }, "trim"]
```

### Validate assertion catalog

| Assertion         | Args                                                   | Meaning                                              |
|-------------------|--------------------------------------------------------|------------------------------------------------------|
| `fieldsLe`        | `{ a: "fieldA", b: "fieldB" }`                         | Parsed value of `fieldA` <= parsed value of `fieldB` |
| `fieldsGe`        | `{ a: "fieldA", b: "fieldB" }`                         | `fieldA` >= `fieldB`                                 |
| `sumApproxEquals` | `{ parts: ["f1","f2"], total: "f3", tolerance: 0.02 }` | `abs(sum(parts) - total) <= tolerance`               |
| `fieldNotNull`    | `{ field: "fieldName" }`                               | Parsed field is not null                             |

### Registry implementation sketch

```kotlin
@Singleton
class TransformRegistry @Inject constructor() {

    val version: String = "1.0.0"

    private val transforms: Map<String, (String?) -> Any?> = mapOf(
        "parseCurrency" to { parseCurrency(it) },
        "parseDistance" to { parseDistance(it) },
        "sha256" to { generateSha256(it.orEmpty()) },
        "trim" to { it?.trim() },
        "toInt" to { it?.toIntOrNull() },
        "toDouble" to { it?.toDoubleOrNull() },
        // ... all registered transforms
    )

    private val parameterized: Map<String, (String?, Map<String, Any>) -> Any?> = mapOf(
        "stripPrefix" to { v, args -> v?.removePrefix(args["prefix"] as String) },
        "replace" to { v, args -> v?.replace(args["pattern"] as String, args["with"] as String) },
        "split" to { v, args ->
            v?.split(args["delimiter"] as String)?.getOrNull((args["index"] as Number).toInt())
        },
        "regex" to { v, args ->
            val match = Regex(args["pattern"] as String).find(v.orEmpty())
            match?.groupValues?.getOrNull((args["group"] as Number).toInt())
        },
        // ... all parameterized transforms
    )

    fun apply(name: String, value: String?): Any? =
        transforms[name]?.invoke(value)
            ?: throw RuleCompileException("Unknown transform: $name")

    fun apply(spec: Map<String, Any>, value: String?): Any? {
        val name = spec.keys.first()  // "replace", "split", etc.
        return parameterized[name]?.invoke(value, spec)
            ?: throw RuleCompileException("Unknown parameterized transform: $name")
    }

    // --- Transform implementations (extracted from UtilityFunctions + parsers) ---

    private fun parseCurrency(text: String?): Double? { /* ... */ }
    private fun parseDistance(text: String?): Double? { /* ... */ }
    private fun generateSha256(input: String): String { /* ... */ }
    // ... all implementations live here, not in UtilityFunctions
}
```

---

## Representative Examples

### DashPausedMatcher (simple: require + parse)

```json5
{
  id: "doordash.screen.dash_paused",
  priority: 10,
  overrideable: true,
  target: "DASH_PAUSED",
  require: { all: [
    { exists: { hasText: "Dash Paused" } },
    { exists: { all: [{ id: "resumeButton" }, { hasDesc: "Resume dash" }] } },
    { exists: { id: "progress_number" } },
  ]},
  parse: {
    fields: {
      timeRemaining: {
        find: { id: "progress_number" },
        read: "text",
        transform: "parseDuration",
        fallback: "35:00",
      },
    },
  },
}
```

### SensitiveScreenMatcher (overrideable: false — CI hard gate)

```json5
{
  id: "doordash.screen.sensitive",
  priority: 0,
  overrideable: false,
  target: "SENSITIVE",
  require: { allTextContainsAny: [
    "Bank Account", "Routing Number", "Verify Identity", "Social Security",
    "Crimson", "Biometric", "Available Balance", "View card details",
    "Linked accounts", "Debit card", "Account number", "Statements & documents",
    "Card status", "Lock card", "Emergency contact details", "Withdraw",
    "Expiry", "Enter the code we sent", "t=completed_view",
  ]},
}
```

### IdleMapMatcher (reject + require + conditional enum parse)

```json5
{
  id: "doordash.screen.idle_map",
  priority: 1,
  overrideable: true,
  target: "MAIN_MAP_IDLE",
  reject: [
    { exists: { hasText: "Return to dash" } },
    { exists: { any: [
      { hasTextContaining: "looking for offers" },
      { hasTextContaining: "finding offers" },
    ]}},
  ],
  require: { all: [
    { exists: { hasDesc: "Earnings Mode Switcher" } },
    { exists: { any: [
      { id: "side_nav_compose_view" },
      { hasDesc: "Side Menu" },
    ]}},
  ]},
  parse: {
    fields: {
      dashType: {
        conditionalEnum: [
          { if: { exists: { hasDesc: "Time mode off" } }, then: "PER_OFFER" },
          { else: "TIME" },
        ],
      },
    },
  },
}
```

### PickupNavigationMatcher (bind + reject on bound node)

```json5
{
  id: "doordash.screen.pickup_navigation",
  priority: 10,
  overrideable: true,
  target: "PICKUP_NAVIGATION",
  bind: {
    taskTitle: { find: { id: "bottom_sheet_task_title" } },
    arriveBy:  { find: { id: "arrive_by_text" }, optional: true },
  },
  reject: [
    { on: "$taskTitle", hasTextContaining: "Deliver to" },
    { on: "$arriveBy",  hasTextContaining: "Deliver by" },
  ],
  parse: {
    fields: {
      storeName: {
        from: "$taskTitle",
        read: "text",
        transform: { stripPrefix: "Pick up from " },
      },
      address: {
        join: {
          parts: [
            { find: { id: "contact_action_address_first_line" }, read: "text" },
            { find: { id: "contact_action_address_second_line" }, read: "text" },
          ],
          separator: ", ",
          skipNulls: true,
        },
        transform: "sha256",
      },
      deadline: {
        find: { id: "arrive_by_text" },
        read: "text",
        transform: "parseDeadline",
      },
    },
  },
}
```

### DeliverySummary (multi-rule + validate)

Two separate rules handle expanded vs collapsed states. The matcher encodes the structural
invariant; validate confirms the pay breakdown is numerically consistent before committing
to EXPANDED. If validate fails, the next rule (COLLAPSED) is tried.

```json5
// Rule 1: Expanded (pay breakdown visible — validate confirms consistency)
{
  id: "doordash.screen.delivery_summary.expanded",
  priority: 15,
  overrideable: true,
  target: "DELIVERY_SUMMARY_EXPANDED",
  reject: [
    { allTextContains: "pause orders" },
  ],
  require: { all: [
    { exists: { id: "final_value" } },
    { allTextContainsAll: ["doordash pay", "customer tips"] },
    { any: [
      { allTextContains: "This offer" },
      { allTextContains: "Delivery Complete" },
    ]},
  ]},
  parse: {
    fields: {
      totalPay:       { find: { id: "final_value" },     read: "text", transform: "parseCurrency" },
      sessionEarnings:{ find: { id: "earnings_ticker" },  read: "text", transform: "parseCurrency" },
      doorDashPay:    { textAfterLabel: "DoorDash pay",   offset: 1,    transform: "parseCurrency" },
      customerTips:   { textAfterLabel: "Customer tips",  offset: 1,    transform: "parseCurrency" },
    },
  },
  validate: [
    {
      assert: "sumApproxEquals",
      args: { parts: ["doorDashPay", "customerTips"], total: "totalPay", tolerance: 0.02 },
      onFail: "skip",  // try next rule (COLLAPSED)
    },
  ],
},
// Rule 2: Collapsed (pay breakdown not yet tapped open — no validate needed)
{
  id: "doordash.screen.delivery_summary.collapsed",
  priority: 16,
  overrideable: true,
  target: "DELIVERY_SUMMARY_COLLAPSED",
  reject: [
    { allTextContains: "pause orders" },
  ],
  require: { all: [
    { exists: { id: "final_value" } },
    { not: { allTextContains: "doordash pay" } },
    { any: [
      { allTextContains: "This offer" },
      { allTextContains: "Delivery Complete" },
    ]},
  ]},
  parse: {
    fields: {
      totalPay:       { find: { id: "final_value" },     read: "text", transform: "parseCurrency" },
      sessionEarnings:{ find: { id: "earnings_ticker" },  read: "text", transform: "parseCurrency" },
    },
  },
}
```

### RatingsViewParser (pair-walking with each)

```json5
{
  id: "doordash.screen.ratings",
  priority: 25,
  overrideable: true,
  target: "RATINGS_VIEW",
  require: { allTextContainsAll: ["acceptance rate", "completion rate", "on-time rate", "customer rating"] },
  parse: {
    fields: {
      // Pair-walk: each textView_title paired with its sibling textView_description
      metrics: {
        each: {
          findAll: { id: "textView_title" },
          extract: {
            label: { read: "text" },
            value: {
              navigate: "sibling(1)",
              rejectIf: { not: { id: "textView_description" } },
              read: "text",
            },
          },
        },
      },
      // Individual metrics extracted from the pair-walk by label lookup
      acceptanceRate:    { textAfterLabel: "Acceptance rate",    offset: 1, transform: "parsePercent" },
      completionRate:    { textAfterLabel: "Completion rate",    offset: 1, transform: "parsePercent" },
      onTimeRate:        { textAfterLabel: "On-time rate",       offset: 1, transform: "parsePercent" },
      customerRating:    { textAfterLabel: "Customer rating",    offset: 1, transform: "toDouble" },
      lifetimeDeliveries:{ textAfterLabel: "Lifetime deliveries",offset: 1, transform: "toInt" },
    },
  },
}
```

---

## Click Rules

A click rule classifies a single clicked `UiNode` into a `ClickInfo` subtype. Rules are evaluated
in ascending `priority` order; the first match wins.

Clicks operate on a **single node** — the element that was tapped. There is no tree to traverse,
so `exists`/`notExists`/`allTextContains*` are not available. Node predicates apply directly.
The `require` block contains a node-level predicate (not tree-level). There is no `bind` or
`reject` phase for click rules — the single-node context makes them unnecessary.

```json5
{
  id: "doordash.click.accept_offer",
  priority: 10,
  overrideable: true,
  target: "AcceptOffer",
  require: { id: "accept_button" },
}

{
  id: "doordash.click.decline_offer",
  priority: 20,
  overrideable: true,
  target: "DeclineOffer",
  require: { hasText: "Decline offer" },
}

{
  id: "doordash.click.arrived_at_store",
  priority: 30,
  overrideable: true,
  target: "ArrivedAtStore",
  require: { all: [
    { id: "primary_action_button" },
    { any: [{ hasText: "Arrived at store" }, { hasText: "Arrived" }] },
  ]},
}

{
  id: "doordash.click.sensitive",
  priority: 1,
  overrideable: false,
  target: "Sensitive",
  require: { any: [
    { hasTextContaining: "Bank Account" },
    { hasTextContaining: "Withdraw" },
    { hasIdContaining: "bank" },
    { hasIdContaining: "payment_method" },
  ]},
}
```

Click rules do not have a `parse` block — `ClickInfo` subtypes carry no extracted data
beyond `Unknown(nodeId, nodeText)`.

### Special Click Types

| Target       | Behavior                                                                        |
|--------------|---------------------------------------------------------------------------------|
| `Sensitive`  | Click is suppressed; no `ClickEvent` emitted. `overrideable: false`.            |
| `Irrelevant` | Click acknowledged but no `ClickEvent` emitted.                                 |
| `Unknown`    | Implicit fallback — `ClickInfo.Unknown(nodeId, nodeText)` emitted for analysis. |

---

## Notification Rules

A notification rule classifies a `RawNotificationData` payload into a `NotificationInfo`
subtype. Rules are evaluated in ascending `priority` order; the first match wins.

Notifications have flat scalar fields (`title`, `text`, `bigText`, `tickerText`). Predicates
operate on those fields directly — no tree traversal, no bind/reject phases.

```json5
{
  id: "doordash.notification.additional_tip",
  priority: 10,
  overrideable: true,
  target: "AdditionalTip",
  require: { anyFieldMatchesRegex: "added \\$([\\d.]+) tip on a past (.+?) order delivered at (.+)" },
  // Named capture groups extracted from the matching regex
  parse: {
    fields: {
      amount:      { fromGroup: 1, transform: "toDouble" },
      storeName:   { fromGroup: 2, transform: "trim" },
      deliveredAt: { fromGroup: 3, transform: "trim" },
    },
  },
}

{
  id: "doordash.notification.new_order",
  priority: 20,
  overrideable: true,
  target: "NewOrder",
  require: { titleContains: "new order" },
}

{
  id: "doordash.notification.scheduled_expired",
  priority: 30,
  overrideable: true,
  target: "ScheduledDashExpired",
  require: { all: [
    { anyFieldContains: "scheduled" },
    { anyFieldContains: "expired" },
  ]},
}

{
  id: "doordash.notification.sensitive",
  priority: 1,
  overrideable: false,
  target: "Sensitive",
  require: { anyFieldContainsAny: [
    "Bank Account", "Routing Number", "Social Security",
    "Verify Identity", "Biometric", "Account number",
  ]},
}
```

### Notification Predicate Vocabulary

| Predicate                            | Field         | Meaning                                            |
|--------------------------------------|---------------|----------------------------------------------------|
| `{ titleEquals: "s" }`               | `title`       | case-insensitive equals                            |
| `{ titleContains: "s" }`             | `title`       | case-insensitive contains                          |
| `{ titleMatchesRegex: "p" }`         | `title`       | regex match                                        |
| `{ textEquals: "s" }`                | `text`        | case-insensitive equals                            |
| `{ textContains: "s" }`              | `text`        | case-insensitive contains                          |
| `{ textMatchesRegex: "p" }`          | `text`        | regex match                                        |
| `{ bigTextContains: "s" }`           | `bigText`     | case-insensitive contains                          |
| `{ bigTextMatchesRegex: "p" }`       | `bigText`     | regex match                                        |
| `{ tickerTextContains: "s" }`        | `tickerText`  | case-insensitive contains                          |
| `{ tickerTextMatchesRegex: "p" }`    | `tickerText`  | regex match                                        |
| `{ isClearable }`                    | `isClearable` | persistent vs transient                            |
| `{ anyFieldContains: "s" }`          | all           | Any field contains `s`                             |
| `{ anyFieldContainsAll: ["a","b"] }` | all           | All strings present somewhere across fields        |
| `{ anyFieldContainsAny: ["a","b"] }` | all           | At least one string present                        |
| `{ anyFieldMatchesRegex: "p" }`      | all           | Regex against `toFullString()` (all fields joined) |
| `{ all: [ pred, ... ] }`             | ---           | AND                                                |
| `{ any: [ pred, ... ] }`             | ---           | OR                                                 |
| `{ not: pred }`                      | ---           | NOT                                                |

### Special Notification Types

| Target       | Behavior                                                              |
|--------------|-----------------------------------------------------------------------|
| `Sensitive`  | Notification dropped. `overrideable: false`.                          |
| `Irrelevant` | Acknowledged but no `NotificationEvent` emitted.                      |
| `Unknown`    | Implicit fallback — `NotificationInfo.Unknown(rawText)` for analysis. |

---

## Special Screen Types

| Target       | Behavior                                                                                 |
|--------------|------------------------------------------------------------------------------------------|
| `SENSITIVE`  | Blocks all further processing; event is dropped. `overrideable: false` enforced by CI.   |
| `IRRELEVANT` | Matcher fires but no `ScreenEvent` is emitted. For loading screens, splash screens, etc. |
| `UNKNOWN`    | Implicit fallback — no rule matched. Captured in debug builds for inbox review.          |

---

## Interpreter Design

### Compile-Once, Lambda Hot Path

```
App startup / rules refresh
  |
  v
Parse JSON -> AST (RuleNode / NodePredicate / ParseField sealed classes)
  |
  v
Compile each rule's phases -> lambda trees
  - bind:     List<BindEntry>          (each = findNode lambda + name)
  - reject:   List<(UiNode) -> Boolean>   (any true -> skip)
  - require:  (UiNode) -> Boolean     (must be true)
  - parse:    (UiNode, Bindings) -> Map<String, Any?>  (field extractors)
  - validate: List<(Map<String, Any?>) -> Boolean>    (optional post-parse checks)
  |
  v
Store ScreenRuleset, ClickRuleset, NotificationRuleset in memory
  |
  v (hot path -- every accessibility event)

ruleset.matchFirst(tree) -> target Screen / ClickInfo / NotificationInfo
  = iterate sorted rules, invoke pre-compiled lambdas, return on first match
```

Compile time is ~10-50ms for a typical rule file. The hot path invokes pre-compiled JVM
lambdas; no JSON parsing, no reflection. The JVM inlines small lambdas aggressively.

### Data Model Enrichment

To keep the interpreter lean, several helpers are added to the data models. These internalize
patterns that appear across many matchers and parsers, so the interpreter dispatches to them
rather than reimplementing the logic.

**`UiNode` additions** (already present from Phase A2):

```kotlin
// Walk N levels up the parent chain; null if fewer than N ancestors exist
fun ancestor(n: Int): UiNode?

// Return the sibling at (this.indexInParent + offset); null if out of bounds
fun sibling(offset: Int): UiNode?

// Find label in allText DFS list and return the string at label+offset
fun textAfterLabel(label: String, offset: Int = 1): String?

// True when viewIdResourceName is non-null and non-blank
val hasViewId: Boolean
```

**`UiNode` additions** (new for v2 — performance):

```kotlin
// O(1) ID lookup: maps viewIdResourceName suffix to all matching nodes
// Lazy — computed on first access, cached for the tree's lifetime
val idIndex: Map<String, List<UiNode>> by lazy {
    val map = mutableMapOf<String, MutableList<UiNode>>()
    fun index(node: UiNode) {
        node.viewIdResourceName?.substringAfterLast("/")?.let { suffix ->
            map.getOrPut(suffix) { mutableListOf() }.add(node)
        }
        node.children.forEach { index(it) }
    }
    index(this)
    map
}

// O(1) allText membership check: pre-joined, pre-lowercased
// Replaces repeated allText.joinToString().lowercase().contains() in matchers
val allTextLowerJoined: String by lazy {
    allText.joinToString(" | ").lowercase()
}
```

The `idIndex` turns `findNode { it.matchesId("store_name") }` (O(n) tree walk) into
`idIndex["store_name"]?.firstOrNull()` (O(1) map lookup). The `allTextLowerJoined` is
computed once and reused across all `allTextContains*` predicates for the same tree — the
current implementation recomputes the joined+lowercased string per rule.

**`RawNotificationData` additions:**

```kotlin
// Lazy — computed once, used by anyFieldContains/anyFieldMatchesRegex predicates
val fullText: String by lazy { toFullString() }
```

### DSL-to-Interpreter Mapping

| DSL construct                | Interpreter call                                  |
|------------------------------|---------------------------------------------------|
| `find: { id: "s" }`          | `tree.idIndex["s"]?.firstOrNull()`                |
| `findAll: { id: "s" }`       | `tree.idIndex["s"].orEmpty()`                     |
| `navigate: "sibling(N)"`     | `currentNode.sibling(N)`                          |
| `navigate: "ancestor(N)"`    | `currentNode.ancestor(N)`                         |
| `textAfterLabel: "label"`    | `tree.textAfterLabel("label", offset)`            |
| `allTextContains: "s"`       | `tree.allTextLowerJoined.contains(s.lowercase())` |
| `transform: "parseCurrency"` | `TransformRegistry.apply("parseCurrency", value)` |
| `{ hasNoId }`                | `!node.hasViewId`                                 |
| `presence: <pred>`           | compiled predicate returns `Boolean` directly     |

### Dual-Running Validation (debug builds only)

While both Kotlin matchers and JSON-interpreted rules are active, every event is classified by
both. Disagreements are logged at WARN level. Agreement is logged at VERBOSE. Release builds
use the JSON interpreter exclusively once parity is confirmed via issue #213
(`JsonVsKotlinAgreementTest` — all 710+ snapshots must agree).

```kotlin
// Debug-only dual-runner (conceptual — already implemented in classifiers)
if (BuildConfig.DEBUG && jsonRuleset != null) {
    val jsonResult = jsonRuleset.matchFirst(node)
    if (jsonResult != kotlinResult) {
        Timber.w("MATCHER_DISAGREE — kotlin=$kotlinResult json=$jsonResult")
    }
}
```

---

## Security Considerations

The interpreter is the trust boundary between the signed rule file and the app. A malicious or
malformed rule file must not crash the app, exhaust memory, cause runaway CPU usage, or bypass
`overrideable: false` protections.

### 1. Signature before parse

The Ed25519 signature is verified **before the JSON is parsed**. A file that fails verification
is discarded immediately — its bytes never reach the JSON deserializer.

### 2. File size cap (1 MB)

The fetched payload is rejected if its byte length exceeds 1 MB. Legitimate rule files are
expected to be well under 100 KB.

### 3. AST depth cap (20 levels)

The compiler tracks nesting depth and throws `RuleCompileException` if it exceeds 20 levels.
The deepest current rule is 5 levels.

### 4. ReDoS mitigation (regex length + timeout)

Every regex pattern is validated at compile time:

- **Max pattern length: 200 characters.**
- **Compile-time timeout: 50 ms.** Regex construction inside a coroutine deadline.
- **Match-time timeout: 5 ms per invocation.** Returns `false` and logs on timeout.

### 5. `each` result cap (50 nodes)

The `each` source caps the collected list at 50 entries. Legitimate `each` results are 1-12
nodes (OfferParser: 1-3 orders; TimelineViewParser: 1-6 tasks; RatingsViewParser: ~12 metrics).

### 6. `ancestor()` depth cap (100)

Any `ancestor` or `scope.ancestor` value greater than 100 is rejected at compile time.

### 7. `overrideable: false` enforcement

`overrideable: false` is a **CI hard gate** (no community PR can merge changes to these rules)
**and** an in-app enforcement point. At startup, the interpreter:

1. Records the `id`, `target`, and `require` of every `overrideable: false` rule from the
   bundled defaults.
2. When loading a fetched rule file, verifies every `overrideable: false` rule is present
   with the same `target` and equivalent `require` (deep AST equality).
3. If any `overrideable: false` rule is missing or changed, the fetched file is rejected.

### 8. JSON5 is server-side only

The canonical `rules.json` is always plain JSON. The in-app interpreter has no JSON5 parser.

### 9. TransformRegistry is closed

Rule files can only invoke transforms registered in the `TransformRegistry`. Unknown transform
names fail at compile time. The registry contains no `eval`, no reflection, no dynamic dispatch
beyond the registered map. This prevents rule files from expanding the code execution surface.

---

## Versioning Strategy

| Field            | Purpose                                                                                                                                   |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `format_version` | Integer. Increments on breaking DSL changes (new predicate types, changed field semantics). App refuses `format_version > MAX_SUPPORTED`. |
| `engine_version` | Semver. Minimum `TransformRegistry` version required. App rejects if its registry is older.                                               |

Rules are signed with an Ed25519 developer key checked into the app. The detached signature
ships as `rules.json.sig` beside `rules.json`. Custom user-configured URLs may be unsigned
(warning displayed) or configured with a trusted public key.

---

## Fallback Behavior

1. App launches -> checks DataStore for cached rules (last-known-good).
2. Cached rules valid (signature OK, `engine_version` met, `format_version` supported)? -> Use.
3. Otherwise -> fall back to bundled `rules.default.json` (ships in APK assets).
4. Background: WorkManager checks for updates daily + on DoorDash foreground.
5. Fetch fails -> continue with current cached rules; no session interruption.
6. New rules fetched and verified -> update cache; interpreter recompiles in background.

---

## Server-Side Requirements

Static file hosting only. No server-side compute.

- **Distribution URL:** `raw.githubusercontent.com/sjtrotter/dashbuddy-matchers/main/rules.json`
- **Signature:** `rules.json.sig` (Ed25519 detached)
- **Cost:** $0 (CDN-cached GitHub raw)
- **Latency to publish a fix:** push to `main` -> CDN propagates within minutes -> apps pick
  up on next DoorDash foreground event or daily WorkManager tick
- **Branch pinning:** for beta/canary, the rules URL can point to a specific branch or SHA

---

## Migration from v1

The shipped `rules.default.json` (Phase A2, PR #210) uses v1 vocabulary (`guards`, `if`,
flat `parse.fields` with `source` objects). The v2 DSL is a superset:

| v1 concept                                       | v2 equivalent                           |
|--------------------------------------------------|-----------------------------------------|
| `guards: [...]`                                  | `reject: [...]`                         |
| `if: { ... }`                                    | `require: { ... }`                      |
| `source: { nodeByIdSuffix: "s", field: "text" }` | `find: { id: "s" }, read: "text"`       |
| `source: { allTextAt: "label", offset: N }`      | `textAfterLabel: "label", offset: N`    |
| `source: { forEach: { ... } }`                   | `each: { ... }`                         |
| `source: { conditionalEnum: [...] }`             | `conditionalEnum: [...]`                |
| `source: { presence: <pred> }`                   | `presence: <pred>`                      |
| `source: { any: [...] }`                         | `coalesce: [...]`                       |
| `source: { combineFields: [...] }`               | `join: { parts: [...] }`                |
| `transform: "regexGroup:pat:N"`                  | `transform: { regex: "pat", group: N }` |
| `transform: "stripPrefix:s"`                     | `transform: { stripPrefix: "s" }`       |

The interpreter can support both vocabularies during migration (Phase A3). Once all rules are
migrated and the agreement test (#213) is green, v1 support is dropped.

---

## Tasks Completed by This ADR

- [x] Define JSON schema for matcher rules (screens, clicks, notifications)
- [x] Define versioning strategy (`format_version`, `engine_version`)
- [x] Define fallback behavior (DataStore cache -> bundled default)
- [x] Estimate server-side requirements (static file, zero cost)
- [x] Define 5-phase evaluation pipeline (bind -> reject -> require -> parse -> validate)
- [x] Define parse sub-language (find, read, transform, each, navigate, join, coalesce)
- [x] Define TransformRegistry (engine-owned, closed vocabulary)
- [x] Define platform envelope header
- [x] Validate DSL completeness against all 31 matchers and 17 parsers
- [x] Define data model enrichment (UiNode.idIndex, allTextLowerJoined)

## Implementation Issues

- **#87** (this issue) -- upgraded from RFC to spec by this ADR
- **Phase A1**: schema definition <- this document
- **Phase A2**: build the in-app interpreter (shipped via PR #210 -- uses v1 vocabulary)
- **Phase A3**: migrate to v2 DSL; `JsonVsKotlinAgreementTest` (#213) passes all snapshots
- **Phase A4**: hosting + Ed25519 signing + signature verification on client
- **Phase C1+**: `dashbuddy-matchers` repo, CI/CD, community contribution model
