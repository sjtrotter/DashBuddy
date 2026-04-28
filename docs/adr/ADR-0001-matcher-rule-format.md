# ADR-0001: Matcher / Classifier Rule Format for OTA Updates

**Status:** Accepted  
**Issue:** #87 (sub-RFC of Epic #192)  
**Date:** 2026-04-28

---

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

---

## Decision

**Rule format:** JSON5 for human authoring; converted to canonical signed JSON for distribution.  
YAML was considered and rejected: the Norway problem (boolean coercion of country codes),
indentation sensitivity, and multi-document footguns all make it dangerous for community PRs.
JSON5 gives us comments and trailing commas without those risks.

**Interpreter:** Compile-once approach. At startup (or when new rules are fetched), the JSON is
parsed once into a `RuleNode` sealed-class AST, then compiled into `(UiNode) -> Boolean` (for
screens/clicks) or `(RawNotificationData) -> Boolean` (for notifications) lambda trees stored in
memory. Every hot-path invocation is a pure lambda call — no parsing, no reflection.

**Dual-running during development:** While the Kotlin matchers/classifiers coexist with the
interpreter, debug builds run both and log any disagreement. This validates the JSON rules against
real traffic before the Kotlin implementations are removed.

**Parsers:** Every parser is fully expressible in the DSL. There are no `native` fallbacks — any
fix must be shippable OTA without a Play Store release. Where existing Kotlin parsers use complex
procedural logic, the rule format provides extended primitives (`forEach`, `siblingAfter`,
`allTextAt`, `conditionalEnum`, `presence`, `regexGroup`) or the matching/parsing strategy is
redesigned to eliminate the complexity.

---

## Rule File Structure

```json5
// rules.json5 — human-authored source
// Tooled to produce rules.json (canonical JSON) for distribution
{
  format_version: 1,           // integer; bumped when the DSL gains new predicate types
  platform_id: "doordash.driver",
  min_app_version: "1.0.0",   // app refuses to apply rules requiring a newer version

  screens: [ /* ScreenRule[] */ ],
  clicks:  [ /* ClickRule[]  */ ],
  notifications: [ /* NotificationRule[] */ ],
}
```

`platform_id` is baked into every rule so the same interpreter can handle a future
`uber.driver` or `instacart.shopper` rule file without changes to the host app.

---

## Screen Rules

A screen rule identifies which `Screen` enum value a given `UiNode` tree represents.
Rules are evaluated in ascending `priority` order; the first match wins.

### Shape

```json5
{
  id: "doordash.screen.dash_paused",        // dotted namespace: platform.pipeline.name
  platform_id: "doordash.driver",
  priority: 10,                              // lower number = evaluated earlier
  overrideable: true,                        // false = CI hard gate; community PRs cannot change

  // --- Single-target form (most matchers) ---
  target: "DASH_PAUSED",                     // Screen enum name
  guards: [                                  // if ANY guard passes, this rule returns null immediately
    // tree predicates (see vocabulary below)
  ],
  if: { /* tree predicate — must be true for the rule to match */ },

  // --- Parser ---
  parse: { /* see Parser section */ },
}
```

For matchers that can return one of several screens based on tree content, use `branches`
instead of `target/guards/if`. The first branch whose `if` passes wins.

```json5
{
  id: "doordash.screen.offer",
  platform_id: "doordash.driver",
  priority: 20,
  overrideable: true,
  branches: [
    {
      target: "OFFER_POPUP_CONFIRM_DECLINE",
      if: { exists: { hasTextContaining: "sure you want to decline" } }
    },
    {
      target: "OFFER_POPUP",
      guards: [
        { exists: { hasIdSuffix: "progress_bar" } }
      ],
      if: { all: [
        { exists: { hasText: "Decline" } },
        { exists: { any: [{ hasText: "Accept" }, { hasText: "Add to route" }] } }
      ]}
    }
  ],
  // parse block: forEach per-order card — see Parser DSL → forEach section for full example
  parse: { /* orders, storeName, pay, distance — forEach { nodes: hasIdSuffix "display_name", scope.ascend: 2, ... } */ },
}
```

### Tree-Level Predicate Vocabulary

These predicates operate on the full `UiNode` tree:

| Predicate | Meaning | Kotlin equivalent |
|---|---|---|
| `{ exists: <nodePred> }` | Some node in tree satisfies predicate | `tree.findNode { ... } != null` |
| `{ notExists: <nodePred> }` | No node in tree satisfies predicate | `tree.findNode { ... } == null` |
| `{ allTextContains: "s" }` | Joined lowercase text of all nodes contains `s` (case-insensitive) | `tree.allText.joinToString(" \| ").lowercase().contains(s.lowercase())` |
| `{ allTextContainsAll: ["a","b"] }` | All strings present in joined text | AND of `allTextContains` |
| `{ allTextContainsAny: ["a","b"] }` | Any string present in joined text | OR of `allTextContains` |
| `{ all: [ pred, ... ] }` | All child predicates must pass | logical AND |
| `{ any: [ pred, ... ] }` | At least one child predicate must pass | logical OR |
| `{ not: pred }` | Negate | logical NOT |

### Node-Level Predicate Vocabulary

These predicates operate on a single `UiNode`. Used inside `exists`/`notExists`, or combined
with `all`/`any` at the node level.

| Predicate | Meaning | Kotlin equivalent |
|---|---|---|
| `{ hasIdSuffix: "s" }` | `viewIdResourceName` ends with `s` | `.endsWith("s")` |
| `{ hasIdExact: "s" }` | `viewIdResourceName` exactly equals `s` | `== "s"` |
| `{ hasIdContaining: "s" }` | `viewIdResourceName` contains `s` | `.contains("s")` |
| `{ hasNoId }` | `viewIdResourceName` is null or blank | null/blank check |
| `{ hasText: "s" }` | `text` equals `s` **(case-insensitive)** | `.equals("s", ignoreCase=true)` |
| `{ hasTextCaseSensitive: "s" }` | `text` exactly equals `s` **(case-sensitive)** | `== "s"` |
| `{ hasTextContaining: "s" }` | `text` contains `s` (case-insensitive) | `.contains("s", ignoreCase=true)` |
| `{ hasTextStartsWith: "s" }` | `text` starts with `s` (case-insensitive) | `.startsWith("s", ignoreCase=true)` |
| `{ hasTextMatchesRegex: "p" }` | `text` matches regex pattern `p` | `Regex(p).containsMatchIn(text)` |
| `{ hasDesc: "s" }` | `contentDescription` equals `s` (case-insensitive) | `.equals("s", ignoreCase=true)` |
| `{ hasDescContaining: "s" }` | `contentDescription` contains `s` (case-insensitive) | `.contains("s", ignoreCase=true)` |
| `{ hasStateDescription: "s" }` | `stateDescription` equals `s` (case-insensitive) | `.equals("s", ignoreCase=true)` |
| `{ hasStateDescriptionContaining: "s" }` | `stateDescription` contains `s` (case-insensitive) | `.contains("s", ignoreCase=true)` |
| `{ hasClassName: "s" }` | `className` exactly equals `s` | `== "s"` |
| `{ hasClassNameEndsWith: "s" }` | `className` ends with `s` (case-insensitive) | `.endsWith("s", ignoreCase=true)` |
| `{ isClickable }` | node is clickable | `node.isClickable == true` |
| `{ isEnabled }` | node is enabled | `node.isEnabled == true` |
| `{ isChecked }` | node is in checked state | `node.isChecked == 1` |
| `{ hasChildren }` | node has at least one child | `node.children.isNotEmpty()` |
| `{ isLeaf }` | node has no children | `node.children.isEmpty()` |
| `{ all: [ nodePred, ... ] }` | All node predicates must pass | logical AND within a node |
| `{ any: [ nodePred, ... ] }` | At least one node predicate must pass | logical OR within a node |
| `{ not: nodePred }` | Negate a node predicate | logical NOT within a node |

The table above covers the **complete** `UiNode` data model surface. Every field exposed by
`AccessibilityNodeInfo` that DashBuddy captures is addressable via a predicate, so community
contributors do not need to read the Kotlin source to know what is available.

### Representative Examples

**`DashPausedMatcher`:**
```json5
{
  id: "doordash.screen.dash_paused",
  platform_id: "doordash.driver",
  priority: 10,
  overrideable: true,
  target: "DASH_PAUSED",
  if: { all: [
    { exists: { hasText: "Dash Paused" } },
    { exists: { all: [{ hasIdSuffix: "resumeButton" }, { hasDesc: "Resume dash" }] } },
    { exists: { hasIdSuffix: "progress_number" } }
  ]},
  parse: {
    fields: {
      timeRemaining: {
        source: { nodeByIdSuffix: "progress_number", field: "text" },
        fallback: "35:00",
        transform: "parseDuration",
      }
    }
  }
}
```

**`SensitiveScreenMatcher`** (`overrideable: false` is the CI hard gate):
```json5
{
  id: "doordash.screen.sensitive",
  platform_id: "doordash.driver",
  priority: 0,
  overrideable: false,
  target: "SENSITIVE",
  if: { allTextContainsAny: [
    "Bank Account", "Routing Number", "Verify Identity", "Social Security",
    "Crimson", "Biometric", "Available Balance", "View card details",
    "Linked accounts", "Debit card", "Account number", "Statements & documents",
    "Card status", "Lock card", "Emergency contact details", "Withdraw",
    "Expiry", "Enter the code we sent", "t=completed_view"
  ]},
  parse: { fields: {} },  // sensitive screens carry no extracted data
}
```

**`TimelineViewMatcher`** (uses `allTextContainsAll`):
```json5
{
  id: "doordash.screen.timeline",
  platform_id: "doordash.driver",
  priority: 25,
  overrideable: true,
  target: "TIMELINE_VIEW",
  if: { allTextContainsAll: ["dash ends at", "pause orders"] },
  // parse block: tasks forEach + allTextAt session totals — see Parser DSL sections for full example
  parse: {
    fields: {
      tasks:         { source: { forEach: { /* ... */ } } },
      dashEarnings:  { source: { allTextAt: "This dash",  offset: 1 }, transform: "parseCurrency" },
      offerEarnings: { source: { allTextAt: "This offer", offset: 1 }, transform: "parseCurrency" },
    }
  }
}
```

**`IdleMapMatcher`** (guards + contentDescription predicates):
```json5
{
  id: "doordash.screen.idle_map",
  platform_id: "doordash.driver",
  priority: 1,
  overrideable: true,
  target: "MAIN_MAP_IDLE",
  guards: [
    { exists: { hasText: "Return to dash" } },
    { exists: { any: [
      { hasTextContaining: "looking for offers" },
      { hasTextContaining: "finding offers" }
    ]}}
  ],
  if: { all: [
    { exists: { hasDesc: "Earnings Mode Switcher" } },
    { exists: { any: [
      { hasIdSuffix: "side_nav_compose_view" },
      { hasDesc: "Side Menu" }
    ]}}
  ]},
  parse: {
    fields: {
      dashType: {
        source: { conditionalEnum: [
          { if: { exists: { hasDesc: "Time mode off" } }, then: "PER_OFFER" },
          { if: { always: true },                         then: "TIME"      }
        ]}
      }
    }
  }
}
```

**`PickupArrivalMatcher`** (combined id+text predicate):
```json5
{
  id: "doordash.screen.pickup_arrival",
  platform_id: "doordash.driver",
  priority: 8,
  overrideable: true,
  target: "PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE",
  if: { all: [
    { exists: { all: [
      { hasIdSuffix: "customer_name_label" },
      { hasTextContaining: "Order for" }
    ]}},
    { exists: { all: [
      { hasIdSuffix: "textView_prism_button_title" },
      { any: [
        { hasTextContaining: "Confirm" },
        { hasTextContaining: "Continue" },
        { hasTextContaining: "Start" }
      ]}
    ]}}
  ]},
  parse: {
    fields: {
      customerNameHash: {
        source: { nodeByIdSuffix: "customer_name", field: "text" },
        transform: "sha256",
      },
      storeName: {
        source: { nodeByIdSuffix: "instructions_title", field: "text" },
        rejectIf: { any: [{ hasTextContaining: "instructions" }, { hasTextContaining: "notes" }] },
      },
      deadline: {
        source: { nodeWhere: { hasTextStartsWith: "Pick up by" }, field: "text" },
        transform: "parseDeadline",
      },
      itemCount: {
        source: { nodeByIdSuffix: "items_title_v2", field: "text" },
        transform: "parseLeadingInt",
      },
      redCardTotal: {
        source: { nodeByIdSuffix: "banner_label", field: "text" },
        transform: "parseCurrency",
      },
    }
  }
}
```

### Parser DSL

The `parse.fields` block defines one entry per output field. There are no "native" fallbacks —
every parser must be fully expressible in this DSL so any fix can ship OTA without a Play Store
release. Where the current Kotlin parsers use complex procedural logic, the rule format provides
extended primitives, or the matching/parsing strategy is redesigned to be simpler.

#### Design principles

**Matchers as the first validation line.** The matcher `if` predicate should encode as many
screen invariants as possible. A parser only runs when the matcher has already confirmed the
screen is structurally correct. Numeric cross-field validation that cannot be expressed as a
predicate belongs in a `validate` block.

**Structural sources over `allTextAt` where possible.** `UiNode.allText` is a `lazy` property
(computed once, cached per node), so calling it in parsers is not a separate tree walk. However,
`allTextAt` relies on DFS-order adjacency — if DoorDash reorders elements, index offsets shift.
Prefer `nodeByIdSuffix` / `nodeWhere` when a reliable `viewIdResourceName` exists. Use
`allTextAt` only when no viewId is available and the label → value adjacency is a design
invariant of the screen (e.g., DoorDash always renders "This dash" immediately before its
dollar amount). When either ordering or viewId could change, structural is safer.

#### Source types

| Source | Meaning |
|---|---|
| `{ nodeByIdSuffix: "s", field: "text" }` | `findNode { viewIdResourceName?.endsWith("s") }?.text` |
| `{ nodeByIdSuffix: "s", field: "contentDescription" }` | Same but reads `contentDescription` |
| `{ nodeWhere: <nodePred>, field: "text" }` | `findNode { nodePred matches }?.text` |
| `{ allTextAt: "label", offset: N }` | `allText[indexOf(label) + N]`; null if label not found |
| `{ combineFields: [src, src], separator: ", " }` | Join multiple sources with separator; skip nulls |
| `{ any: [src, ...] }` | First non-null source wins — for dual-layout / fallback extraction |
| `{ conditionalEnum: [ { if: <treePred>, then: "VALUE" }, ... ] }` | Presence-based enum; first matching `if` wins; null if none |
| `{ presence: <treePred> }` | Returns `true` if the tree predicate passes, `false` otherwise — cleaner than `conditionalEnum` for boolean fields |
| `{ forEach: { ... } }` | Multi-entity extraction — see below |

**`any` source** handles screens with layout variants or ID-renamed nodes:

```json5
// WaitingForOffer: new layout uses label/value pair; legacy uses button child text
waitTimeEstimate: {
  source: { any: [
    { allTextAt: "Zone offer wait", offset: 1 },     // new layout — design-invariant adjacency
    { nodeByIdSuffix: "textView_prism_button_title", field: "text" } // legacy button
  ]},
  transform: "stripPrefix:est. ",  // strips "est. " when present; no-op otherwise
}

// DashSummaryParser: header_pay was removed late 2025; fall back to first currency-shaped node
totalEarnings: {
  source: { any: [
    { nodeByIdSuffix: "header_pay", field: "text" },
    { nodeWhere: { hasTextMatchesRegex: "^\\$[\\d,]+\\.\\d{2}$" }, field: "text" }
  ]},
  transform: "parseCurrency",
}
```

#### Collection extraction with `forEach`

`forEach` iterates over all tree nodes matching a node predicate, optionally ascending to a
scope ancestor, and extracts one structured object per matched node. The result is a typed list.

`scope.ascend: N` walks N levels up from the matched node to a container; relative sources in
the `extract` block then search within that container rather than the whole tree.

```json5
// OfferParser: each display_name node is inside a per-order card 2 levels up
orders: {
  source: { forEach: {
    nodes: { hasIdSuffix: "display_name" },
    scope: { ascend: 2 },
    exclude: { any: [{ hasText: "Customer dropoff" }, { hasText: "Business handoff" }] },
    extract: {
      storeName: { field: "text" },
      orderType: {
        source: { conditionalEnum: [
          { if: { exists: { all: [{ hasIdSuffix: "work_unit_type" }, { hasTextContaining: "Shop" }] }}, then: "SHOP_FOR_ITEMS" },
          { if: { always: true }, then: "PICKUP" }
        ]}
      },
      itemCount: {
        source: { descendantByIdSuffix: "display_name_secondary", field: "text" },
        transform: "parseItemCount", fallback: 1,
      },
      hasRedCard: {
        source: { presence: { exists: { all: [{ hasIdSuffix: "tag" }, { hasTextContaining: "Red Card" }] } } }
      },
    }
  }}
}
```

```json5
// TimelineViewParser: each task-prefix node is immediately followed by a sibling deadline node
tasks: {
  source: { forEach: {
    nodes: { any: [
      { hasTextStartsWith: "Pickup for " },
      { hasTextStartsWith: "Deliver to " },
      { hasTextStartsWith: "Pickup from " },
    ]},
    extract: {
      taskType: { field: "text", transform: "extractPrefix:[\"Pickup for \",\"Deliver to \",\"Pickup from \"]" },
      nameHash: { field: "text", transform: ["stripPrefixes:[\"Pickup for \",\"Deliver to \",\"Pickup from \"]", "trim", "sha256"] },
      deadline: { source: { siblingAfter: 1, field: "text" }, transform: ["extractBefore: \" • \"", "parseDeadline"] },
      storeHint: { source: { siblingAfter: 1, field: "text" }, transform: "extractAfter: \" • \"" },
      // presence: returns true/false directly — preferred over conditionalEnum for booleans
      isCurrent: { source: { presence: { exists: { hasText: "Current task" } } } },
    }
  }}
}
```

For `allTextAt` patterns that are design-invariant (DoorDash always renders these pairs in
DFS order):

```json5
// TimelineViewParser — session totals
dashEarnings:  { source: { allTextAt: "This dash",  offset: 1 }, transform: "parseCurrency" }
offerEarnings: { source: { allTextAt: "This offer", offset: 1 }, transform: "parseCurrency" }

// DashSummaryParser — label-value rows (label is always immediately before value in DFS)
durationMillis: { source: { allTextAt: "Total online time",  offset: 1 }, transform: "parseHrMin" }
offersAccepted: { source: { allTextAt: "Offers accepted",    offset: 1 }, transform: "parseAccepted" }
offersTotal:    { source: { allTextAt: "Offers accepted",    offset: 1 }, transform: "parseTotal" }
weeklyEarnings: { source: { allTextAt: "Earnings this week", offset: 1 }, transform: "parseCurrency" }

// RatingsViewParser — metric pairs (title immediately precedes description in DFS)
acceptanceRate:    { source: { allTextAt: "Acceptance rate",    offset: 1 }, transform: "parsePercent" }
completionRate:    { source: { allTextAt: "Completion rate",    offset: 1 }, transform: "parsePercent" }
customerRating:    { source: { allTextAt: "Customer rating",    offset: 1 }, transform: "toDouble" }
lifetimeDeliveries:{ source: { allTextAt: "Lifetime deliveries",offset: 1 }, transform: "toInt" }
```

#### Relative sources (within `forEach` extract blocks)

| Source | Meaning |
|---|---|
| `{ field: "text" }` | `currentNode.text` |
| `{ field: "contentDescription" }` | `currentNode.contentDescription` |
| `{ siblingAfter: N, field: "text" }` | `parent.children[indexOf(current) + N].text` |
| `{ descendantByIdSuffix: "s", field: "text" }` | First descendant in scope with that ID suffix |
| `{ always: true }` | Sentinel for the else-branch in `conditionalEnum` |

#### `hasNoId` node predicate

Required for `DropoffPreArrivalParser`'s address lines, which carry no `viewIdResourceName`.
Using content-shape regex avoids the positional index approach in the current Kotlin parser
and is more robust when surrounding node counts change.

| Predicate | Meaning | Kotlin equivalent |
|---|---|---|
| `{ hasNoId }` | `viewIdResourceName` is null or blank | null/blank viewId check |

```json5
// Address lines: first no-viewId node matching street number pattern; second matching zip
addressLine1: { source: { nodeWhere: { all: [{ hasNoId }, { hasTextMatchesRegex: "^\\d{1,5}\\s+\\S" }] }, field: "text" } }
addressLine2: { source: { nodeWhere: { all: [{ hasNoId }, { hasTextMatchesRegex: "\\d{5}$"          }] }, field: "text" } }
address:      { source: { combineFields: ["addressLine1", "addressLine2"], separator: ", " }, transform: "sha256" }
```

#### Transform primitives (auditable catalog; new primitives require an app update)

| Transform | Input → Output |
|---|---|
| `parseCurrency` | `"$5.00"` → `Double` |
| `parseDistance` | `"3.2 mi"` → `Double` |
| `parseDeadline` | `"Pick up by 17:39"`, `"by 6:10 PM"`, `"Spot saved until 15:57"` → `ParsedTime` |
| `parseDuration` | `"35:00"` (MM:SS) → `Long` milliseconds |
| `parseHrMin` | `"2 hr 15 min"` → `Long` milliseconds |
| `parseLeadingInt` | `"4 items"` → `Int` (leading digit sequence) |
| `parseItemCount` | `"2 orders"` or `"4 items"` → `Int` |
| `parseAccepted` | `"3 out of 5"` → `Int` (numerator) |
| `parseTotal` | `"3 out of 5"` → `Int` (denominator) |
| `parsePercent` | `"85.7%"` → `Double` |
| `regexGroup:<pattern>:<N>` | Run regex; return capture group N; null if no match |
| `sha256` | `String` → SHA-256 hex `String` |
| `trim` | strip whitespace |
| `lowercase` | to lowercase |
| `toDouble` | `String` → `Double` |
| `toInt` | `String` → `Int` |
| `stripPrefix:<s>` | if starts with `s`, remove it; otherwise return value unchanged |
| `stripSuffix:<s>` | if ends with `s`, remove it; otherwise return value unchanged |
| `stripPrefixes:[list]` | remove first matching prefix from a JSON array of strings |
| `extractPrefix:[list]` | return the first matching prefix from a JSON array |
| `extractBefore:<s>` | return substring before first `s`; null if `s` not found |
| `extractAfter:<s>` | return substring after first `s`; null if `s` not found |

Transforms chain as an array: `transform: ["stripPrefixes:[...]", "trim", "sha256"]`.

**`rejectIf`:** If the extracted node matches the predicate, treat the field as null.
Used in `PickupArrivalParser` to exclude "Parking instructions" / "Notes" from `storeName`.

**`fallback`:** Static value used when the source resolves to null.

**`validate`:** Checked after all fields are extracted. If it fails, the rule emits
`ScreenInfo.Simple`. Used for numeric cross-field sanity that cannot be a matcher predicate:

```json5
// DashSummaryParser: dash session total must not exceed weekly total
validate: { fieldLe: { field: "totalEarnings", leField: "weeklyEarnings", tolerance: 0.02 } }
```

**`regexGroup` example** — `PickupShoppingParser` extracts the item count from "To shop (N)":

```json5
itemCount: {
  source: { nodeWhere: { hasTextMatchesRegex: "To shop \\(\\d+\\)" }, field: "text" },
  transform: "regexGroup:To shop \\((\\d+)\\):1",
}
```

#### Parser redesign: DeliverySummary expanded vs collapsed

The current `DeliverySummaryParser` determines the screen variant (`EXPANDED` / `COLLAPSED`)
based on whether the pay breakdown is visible. This is a matching invariant, not a parsing
task. Redesign as two rules whose matchers enforce the invariant before the parser runs:

```json5
// Rule 1: Expanded (DoorDash pay and Customer tips both visible — matcher validates this)
{
  id: "doordash.screen.delivery_summary.expanded",
  priority: 15,
  target: "DELIVERY_SUMMARY_EXPANDED",
  if: { all: [
    { exists: { hasIdSuffix: "final_value" } },
    { allTextContainsAll: ["doordash pay", "customer tips"] }   // invariant
  ]},
  parse: {
    fields: {
      totalPay:       { source: { nodeByIdSuffix: "final_value",     field: "text" }, transform: "parseCurrency" },
      sessionEarnings:{ source: { nodeByIdSuffix: "earnings_ticker", field: "text" }, transform: "parseCurrency" },
      doorDashPay:    { source: { allTextAt: "DoorDash pay",  offset: 1 }, transform: "parseCurrency" },
      customerTips:   { source: { allTextAt: "Customer tips", offset: 1 }, transform: "parseCurrency" },
    },
    // Numeric reconciliation: breakdown items should sum close to headline
    validate: { fieldLe: { field: "totalPay", geField: "doorDashPay", tolerance: 0.02 } }
  }
}

// Rule 2: Collapsed (pay breakdown not yet tapped open)
{
  id: "doordash.screen.delivery_summary.collapsed",
  priority: 16,
  target: "DELIVERY_SUMMARY_COLLAPSED",
  if: { all: [
    { exists: { hasIdSuffix: "final_value" } },
    { not: { allTextContains: "doordash pay" } }
  ]},
  parse: {
    fields: {
      totalPay:       { source: { nodeByIdSuffix: "final_value",     field: "text" }, transform: "parseCurrency" },
      sessionEarnings:{ source: { nodeByIdSuffix: "earnings_ticker", field: "text" }, transform: "parseCurrency" },
    }
  }
}
```

### Special Screen Types

| Target | Behavior |
|---|---|
| `SENSITIVE` | Blocks all further processing; event is dropped. `overrideable: false` enforced by CI. |
| `IRRELEVANT` | Matcher fires but no `ScreenEvent` is emitted. Used for loading screens, splash screens, etc. |
| `UNKNOWN` | Implicit fallback — no rule matched. Captured in debug builds for inbox review. |

---

## Click Rules

A click rule classifies a single clicked `UiNode` into a `ClickInfo` subtype. Rules are evaluated
in ascending `priority` order; the first match wins.

Clicks operate on a **single node** — the element that was tapped. There is no tree to traverse,
so `exists`/`notExists`/`allTextContains*` are not available. Node predicates apply directly.

```json5
{
  id: "doordash.click.accept_offer",
  platform_id: "doordash.driver",
  priority: 10,
  overrideable: true,
  target: "AcceptOffer",
  if: { hasIdSuffix: "accept_button" },
}

{
  id: "doordash.click.decline_offer",
  platform_id: "doordash.driver",
  priority: 20,
  overrideable: true,
  target: "DeclineOffer",
  if: { hasText: "Decline offer" },
}

{
  id: "doordash.click.arrived_at_store",
  platform_id: "doordash.driver",
  priority: 30,
  overrideable: true,
  target: "ArrivedAtStore",
  if: { all: [
    { hasIdSuffix: "primary_action_button" },
    { any: [{ hasText: "Arrived at store" }, { hasText: "Arrived" }] }
  ]},
}

{
  id: "doordash.click.sensitive",
  platform_id: "doordash.driver",
  priority: 1,                          // runs before all other click rules
  overrideable: false,
  target: "Sensitive",
  if: { any: [
    { hasTextContaining: "Bank Account" },
    { hasTextContaining: "Withdraw" },
    { hasIdContaining: "bank" },
    { hasIdContaining: "payment_method" },
  ]},
}
```

**Click node predicates** are the same vocabulary as Screen node predicates:
`hasIdSuffix`, `hasIdExact`, `hasIdContaining`, `hasText`, `hasTextContaining`,
`hasTextStartsWith`, `hasTextMatchesRegex`, `hasDesc`, `hasDescContaining`, `all`, `any`, `not`.

### Special Click Types

| Target | Behavior |
|---|---|
| `Sensitive` | Click is suppressed; no `ClickEvent` emitted. `overrideable: false`. |
| `Irrelevant` | Click is acknowledged but no `ClickEvent` emitted. UI state not updated. |
| `Unknown` | Implicit fallback — `ClickInfo.Unknown(nodeId, nodeText)` emitted for field analysis. |

Click rules do not have a `parse` block — the `ClickInfo` subtypes carry data only where needed
(e.g., `Unknown` preserves `nodeId` and `nodeText`; typed subtypes like `AcceptOffer` carry no
additional payload).

---

## Notification Rules

A notification rule classifies a `RawNotificationData` payload into a `NotificationInfo` subtype.
Rules are evaluated in ascending `priority` order; the first match wins.

Notifications have flat scalar fields (`title`, `text`, `bigText`, `tickerText`). Predicates
operate on those fields directly — no tree traversal.

```json5
{
  id: "doordash.notification.additional_tip",
  platform_id: "doordash.driver",
  priority: 10,
  overrideable: true,
  target: "AdditionalTip",
  // anyFieldMatchesRegex tests against toFullString() (all non-null fields joined)
  if: { anyFieldMatchesRegex: "added \\$([\\d.]+) tip on a past (.+?) order delivered at (.+)" },
  // Named capture groups extracted from the matching regex
  extract: {
    amount:      { fromGroup: 1, transform: "parseDouble" },
    storeName:   { fromGroup: 2, transform: "trim" },
    deliveredAt: { fromGroup: 3, transform: "trim" },
  },
}

{
  id: "doordash.notification.new_order",
  platform_id: "doordash.driver",
  priority: 20,
  overrideable: true,
  target: "NewOrder",
  if: { titleContains: "new order" },
}

{
  id: "doordash.notification.scheduled_expired",
  platform_id: "doordash.driver",
  priority: 30,
  overrideable: true,
  target: "ScheduledDashExpired",
  if: { all: [
    { anyFieldContains: "scheduled" },
    { anyFieldContains: "expired" }
  ]},
}

{
  id: "doordash.notification.sensitive",
  platform_id: "doordash.driver",
  priority: 1,
  overrideable: false,
  target: "Sensitive",
  if: { anyFieldContainsAny: [
    "Bank Account", "Routing Number", "Social Security",
    "Verify Identity", "Biometric", "Account number",
  ]},
}
```

### Notification Predicate Vocabulary

Covers the complete `RawNotificationData` field surface so every field is addressable:

| Predicate | Field | Meaning |
|---|---|---|
| `{ titleEquals: "s" }` | `title` | `raw.title.equals(s, ignoreCase=true)` |
| `{ titleContains: "s" }` | `title` | `raw.title.contains(s, ignoreCase=true)` |
| `{ titleMatchesRegex: "p" }` | `title` | regex match against `raw.title` |
| `{ textEquals: "s" }` | `text` | `raw.text.equals(s, ignoreCase=true)` |
| `{ textContains: "s" }` | `text` | `raw.text.contains(s, ignoreCase=true)` |
| `{ textMatchesRegex: "p" }` | `text` | regex match against `raw.text` |
| `{ bigTextContains: "s" }` | `bigText` | `raw.bigText.contains(s, ignoreCase=true)` |
| `{ bigTextMatchesRegex: "p" }` | `bigText` | regex match against `raw.bigText` |
| `{ tickerTextContains: "s" }` | `tickerText` | `raw.tickerText.contains(s, ignoreCase=true)` |
| `{ tickerTextMatchesRegex: "p" }` | `tickerText` | regex match against `raw.tickerText` |
| `{ isClearable }` | `isClearable` | `raw.isClearable == true` — useful for persistent vs transient |
| `{ anyFieldContains: "s" }` | all | Any of title/text/bigText/tickerText contains `s` |
| `{ anyFieldContainsAll: ["a","b"] }` | all | All strings present somewhere across all fields |
| `{ anyFieldContainsAny: ["a","b"] }` | all | At least one string present somewhere across all fields |
| `{ anyFieldMatchesRegex: "p" }` | all | Regex match against `toFullString()` (all non-null fields joined) |
| `{ all: [ pred, ... ] }` | — | AND |
| `{ any: [ pred, ... ] }` | — | OR |
| `{ not: pred }` | — | NOT |

The `extract` block is only valid when `if` uses a regex predicate. Capture groups are 1-indexed.
Transforms are the same primitive catalog as screen parsers.

### Special Notification Types

| Target | Behavior |
|---|---|
| `Sensitive` | Notification is dropped; no `NotificationEvent` emitted. `overrideable: false`. |
| `Irrelevant` | Notification is acknowledged but no `NotificationEvent` emitted. |
| `Unknown` | Implicit fallback — `NotificationInfo.Unknown(rawText)` emitted for field analysis. |

---

## Interpreter Design

### Compile-Once, Lambda Hot Path

```
App startup / rules refresh
  │
  ▼
Parse JSON → RuleNode sealed-class AST
  │
  ▼
Compile each rule's `if` block → (UiNode) -> Boolean lambda
  │
  ▼
Store ScreenRuleset, ClickRuleset, NotificationRuleset in memory
  │
  ▼ (hot path — every accessibility event)
  
ruleset.matchFirst(tree) → target Screen / ClickInfo / NotificationInfo
  = iterate sorted rules, invoke pre-compiled lambda, return on first match
```

Compile time is ~10–50ms for a typical rule file. The hot path invokes pre-compiled JVM lambdas;
no JSON parsing, no reflection. The JVM inlines small lambdas aggressively.

### AST Sketch

```kotlin
sealed class RuleNode {
    // Tree predicates
    data class Exists(val node: NodePredicate) : RuleNode()
    data class NotExists(val node: NodePredicate) : RuleNode()
    data class AllTextContains(val text: String) : RuleNode()
    data class AllTextContainsAny(val texts: List<String>) : RuleNode()
    data class All(val children: List<RuleNode>) : RuleNode()
    data class Any(val children: List<RuleNode>) : RuleNode()
    data class Not(val child: RuleNode) : RuleNode()
}

sealed class NodePredicate {
    data class HasIdSuffix(val suffix: String) : NodePredicate()
    data class HasIdExact(val id: String) : NodePredicate()
    data class HasIdContaining(val s: String) : NodePredicate()
    data class HasText(val text: String) : NodePredicate()
    data class HasTextContaining(val text: String) : NodePredicate()
    data class HasTextStartsWith(val prefix: String) : NodePredicate()
    data class HasTextMatchesRegex(val pattern: Regex) : NodePredicate()
    data class HasDesc(val desc: String) : NodePredicate()
    data class HasDescContaining(val desc: String) : NodePredicate()
    data class All(val children: List<NodePredicate>) : NodePredicate()
    data class Any(val children: List<NodePredicate>) : NodePredicate()
}

// Compile to lambda — called once per rule at startup
fun RuleNode.compile(): (UiNode) -> Boolean = when (this) {
    is Exists -> { tree -> tree.findNode(node.compile()) != null }
    is NotExists -> { tree -> tree.findNode(node.compile()) == null }
    is AllTextContains -> {
        val lower = text.lowercase()
        { tree -> tree.allText.joinToString(" | ").lowercase().contains(lower) }
    }
    is AllTextContainsAny -> {
        val lowers = texts.map { it.lowercase() }
        { tree ->
            val joined = tree.allText.joinToString(" | ").lowercase()
            lowers.any { joined.contains(it) }
        }
    }
    is All -> {
        val compiled = children.map { it.compile() }
        { tree -> compiled.all { pred -> pred(tree) } }
    }
    is Any -> {
        val compiled = children.map { it.compile() }
        { tree -> compiled.any { pred -> pred(tree) } }
    }
    is Not -> {
        val compiled = child.compile()
        { tree -> !compiled(tree) }
    }
}
```

### Data Model Enrichment

To simplify the interpreter implementation and keep the DSL predicates clean, several helpers
should be added to `UiNode` and `RawNotificationData`. These are not new concepts — they
internalize patterns that already appear across many Kotlin matchers and parsers — but surfacing
them as named extensions makes interpreter code obvious and eliminates the need for DSL workarounds.

**`UiNode` helpers:**

```kotlin
// Walk N levels up the parent chain; null if fewer than N ancestors exist
fun UiNode.ancestor(n: Int): UiNode? {
    var current: UiNode? = this
    repeat(n) { current = current?.parent }
    return current
}

// Return the sibling at (this.indexInParent + offset); null if out of bounds
fun UiNode.sibling(offset: Int): UiNode? {
    val siblings = parent?.children ?: return null
    val idx = siblings.indexOf(this) + offset
    return siblings.getOrNull(idx)
}

// Find `label` in this node's DFS allText list and return the string at label+offset.
// Encapsulates the allTextAt source type — interpreter delegates to this instead of
// reimplementing the index arithmetic.
fun UiNode.textAfterLabel(label: String, offset: Int = 1): String? {
    val idx = allText.indexOfFirst { it.equals(label, ignoreCase = true) }
    return if (idx >= 0) allText.getOrNull(idx + offset) else null
}

// Convenience for the common hasNoId predicate — avoids repeating the null/blank check
val UiNode.hasViewId: Boolean get() = !viewIdResourceName.isNullOrBlank()
```

These helpers map directly to DSL constructs:

| DSL construct | Interpreter call |
|---|---|
| `{ siblingAfter: N }` | `currentNode.sibling(N)` |
| `scope: { ascend: N }` in `forEach` | `matchedNode.ancestor(N)` |
| `{ allTextAt: "label", offset: N }` | `tree.textAfterLabel("label", N)` |
| `{ hasNoId }` | `!node.hasViewId` |

**`presence` source:** The `{ presence: <treePred> }` source type compiles to a lambda that
returns `true` / `false` based on whether the tree predicate passes. Internally it reuses the
same compiled predicate as the `exists` tree predicate — no new interpreter machinery needed.

```kotlin
// Compiled result of: { source: { presence: { exists: { hasText: "Current task" } } } }
{ tree: UiNode -> tree.findNode { it.text.equals("Current task", ignoreCase = true) } != null }
```

### Dual-Running Validation (debug builds only)

While both Kotlin matchers and JSON-interpreted rules are active, every event is classified by
both. Disagreements are logged at WARN level with the full serialized UiNode tree so the
offending rule can be debugged. Agreement is logged at VERBOSE. This runs only in debug builds;
release builds use the JSON interpreter exclusively once parity is confirmed and the Kotlin
implementations are retired.

```kotlin
// Debug-only dual-runner (conceptual)
if (BuildConfig.DEBUG && jsonRuleset != null) {
    val kotlinResult = kotlinMatcher.matches(node)
    val jsonResult = jsonRuleset.matchScreen(node)
    if (kotlinResult != jsonResult) {
        Timber.w("MATCHER DISAGREE — kotlin=$kotlinResult json=$jsonResult node=${node.serialize()}")
    }
}
```

---

## Security Considerations

The interpreter is the trust boundary between the signed rule file and the app. A malicious or
malformed rule file must not be able to crash the app, exhaust memory, cause runaway CPU usage,
or bypass `overrideable: false` protections. Eight mitigations are required:

### 1. Signature before parse

The Ed25519 signature is verified **before the JSON is parsed**. A rule file that fails signature
verification is discarded immediately — its bytes never reach the JSON deserializer. This prevents
crafted inputs from exploiting parser edge cases in the JSON library.

### 2. File size cap (1 MB)

The fetched payload is rejected before writing to DataStore if its byte length exceeds 1 MB.
Legitimate rule files are expected to be well under 100 KB; the 1 MB ceiling stops a content
delivery compromise from allocating large buffers.

### 3. AST depth cap (20 levels)

During the compile step, the tree of `RuleNode` / `NodePredicate` objects is walked recursively.
A maliciously deeply-nested predicate can cause a stack overflow. The compiler tracks nesting depth
and throws `RuleCompileException` if it exceeds 20 levels. 20 is far above any legitimate rule;
the deepest current matcher is 5 levels.

### 4. ReDoS mitigation (regex length + timeout)

Every `hasTextMatchesRegex`, `hasDescMatchesRegex`, `titleMatchesRegex`, etc. value is validated
at compile time:

- **Max pattern length: 200 characters.** Any regex longer than this is rejected.
- **Compile-time timeout (50 ms).** The `Regex` object is constructed inside a coroutine with a
  50 ms deadline. If construction times out (pathological backtracking during compilation is
  possible with Java's `java.util.regex`), the rule is rejected.
- **Match-time timeout (5 ms per invocation).** At runtime, regex matches run with an
  interruptible approach (or equivalent timeout wrapper). If a match exceeds 5 ms, it returns
  `false` and logs a warning.

### 5. `forEach` result cap (50 nodes)

The `forEach` source iterates all nodes matching a predicate and builds a list. A malformed rule
could match every node in a large tree (thousands of nodes) and allocate an unbounded list. The
interpreter caps the collected list at 50 entries; additional matches are silently ignored. 50 is
above any legitimate `forEach` result (OfferParser: 1–3 orders; TimelineViewParser: 1–6 tasks).

### 6. `ancestor()` depth cap (100)

`scope: { ascend: N }` calls `UiNode.ancestor(N)`. A rule specifying `ascend: 999999` would walk
the entire parent chain on every matched node. The interpreter rejects any `ascend` value greater
than 100 at compile time.

### 7. `overrideable: false` enforcement in the interpreter

`overrideable: false` is a **CI hard gate** (no community PR can merge a change to these rules)
**and** an in-app enforcement point. At startup, the interpreter:

1. Reads the bundled `rules.default.json` and records the `id`, `target`, and `if` of every rule
   with `overrideable: false`.
2. When loading a fetched rule file, verifies that every `overrideable: false` rule id is present
   with the same `target` and an equivalent `if` block (deep equality of the compiled AST).
3. If any `overrideable: false` rule is missing or has a changed `if`, the fetched file is rejected
   and the bundled defaults are used.

This ensures that even a key compromise cannot produce a signed rule file that removes or weakens
`SENSITIVE` screen protection.

### 8. JSON5 is server-side only

JSON5 is a human-authoring convenience. The canonical `rules.json` distributed by the CDN is
always **plain JSON** (produced by the toolchain from the JSON5 source). The in-app interpreter
has no JSON5 parser. This eliminates the comment-injection and trailing-comma ambiguity surface
that exists in JSON5 parsers.

---

## Versioning Strategy

| Field | Purpose |
|---|---|
| `format_version` | Integer. Increments when the DSL adds new predicate types or changes field semantics. App refuses to load `format_version > MAX_SUPPORTED`. |
| `min_app_version` | Semver. Rules refuse to apply on older app builds. Graceful fallback to bundled defaults. |
| `rules_format_version` | Initial value: `1`. |

Rules are signed with an Ed25519 developer key checked into the app. The detached signature
ships as `rules.json.sig` beside `rules.json` at the distribution URL. Custom user-configured
URLs may be unsigned (warning displayed) or configured with a trusted public key.

---

## Fallback Behavior

1. App launches → checks DataStore for cached rules (last-known-good).
2. Cached rules present and valid (signature OK, `min_app_version` met, `format_version`
   supported)? → Use them.
3. Otherwise → fall back to bundled `rules.default.json` (ships in APK assets).
4. Background: WorkManager job checks for updates daily + opportunistically when DoorDash
   comes to the foreground.
5. Fetch fails → continue with current cached rules; no interruption to the dasher's session.
6. New rules fetched and verified → update DataStore cache; interpreter recompiles in background.
   Next event processed uses the new rules.

---

## Server-Side Requirements

Static file hosting only. No server-side compute.

- **Distribution URL:** `raw.githubusercontent.com/sjtrotter/dashbuddy-matchers/main/rules.json`
- **Signature:** `rules.json.sig` (Ed25519 detached)
- **Cost:** $0 (CDN-cached GitHub raw)
- **Latency to publish a fix:** maintainer pushes to `main` → CDN propagates within minutes →
  apps pick up on next DoorDash foreground event or next daily WorkManager tick
- **Branch pinning:** for beta users or canary testing, the rules URL can point to a specific
  branch or commit SHA

---

## Tasks Completed by This ADR

- [x] Write ADR in docs/adr/ documenting the chosen approach and tradeoffs
- [x] Define JSON schema for matcher rules (screens, clicks, notifications)
- [x] Define versioning strategy (`format_version`, `min_app_version`)
- [x] Define fallback behavior (DataStore cache → bundled default)
- [x] Estimate server-side requirements (static file, zero cost)

## Implementation Issues

- **#87** (this issue) — upgraded from RFC to spec by this ADR
- **Phase A1**: schema definition ← this document
- **Phase A2**: build the in-app interpreter (`RuleNode`, `compile()`, `JsonRulesetLoader`)
- **Phase A3**: `rules.default.json5` reproducing all current matcher behavior; regression suite passes
- **Phase A4**: hosting + Ed25519 signing + signature verification on client
- **Phase C1+**: `dashbuddy-matchers` repo, CI/CD, community contribution model
