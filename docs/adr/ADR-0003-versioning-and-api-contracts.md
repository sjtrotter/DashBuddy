# ADR-0003: Versioning and API Contracts Across Layers

**Status:** Accepted
**Issue:** Sub-RFC of Epic #192; expands #211 Spec 6
**Date:** 2026-04-30
**Builds on:** ADR-0001 (rule format), ADR-0002 (state taxonomy)

---

## Context

ADR-0001 introduced `format_version` and `min_app_version` at the rule file
header. ADR-0002 introduced a `state:` field that references a vocabulary
(mode + flow values) owned by the state machine, not the rule format. The
JSON rule interpreter shipped in #210 has neither check implemented yet
(#211 Spec 6).

Looking at the system as a whole, there are **four distinct layers** that can
each evolve at their own pace, and a missing version check between any two
of them produces a different failure mode:

1. **Pipeline modules** — `WindowPipeline`, `ViewPipeline`, future clipboard
   / OCR / GPS pipelines. Each produces events of a specific shape (`UiNode`,
   `RawNotificationData`, etc.). When the event shape changes, rules reading
   those fields need to know.
2. **Ruleset (the DSL file)** — declares what features it uses
   (`format_version`) and what it depends on (currently nothing beyond
   `min_app_version`).
3. **Engine** — the `RuleCompiler` and interpreter. Has its own evolution
   independent of what the DSL can express (security gates, regex semantics,
   transform null-handling).
4. **State machine** — owns the flow / mode vocabulary, the parsed-data
   schema per flow, the side effects available, the transition semantics.

Currently, only one of those (the ruleset's `format_version`) has a declared
version, and even that isn't checked yet. The other three are implicit.

This works today because there is one app version, one engine version, one
pipeline configuration, and one state machine. The moment any of these
multiplies — a community fork shipping a new pipeline, a second platform's
ruleset emitting new flow values, a tagged release of the matcher repo
running against an older app — the missing contracts produce silent
incompatibilities. A ruleset emitting `flow: "leg:swap:active"` against a
state machine that doesn't know that value fails opaquely; a rule reading
`node.semanticRole` against a pipeline that doesn't expose it fails opaquely;
a community fork's clipboard rules silently no-op on apps without a clipboard
pipeline.

This ADR defines the four-layer versioning model, the ruleset header schema
that declares cross-layer dependencies, and the loader's compatibility-check
algorithm.

---

## Decision

### The four layers

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: Pipeline modules                                    │
│   produce: events (UiNode, RawNotificationData, ClickEvent)  │
│   contract: event shape, semantic guarantees                 │
│   versioned by: API_VERSION constant per pipeline            │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: Ruleset (DSL file)                                  │
│   declares: format_version, min_app_version, platform_id,    │
│             pipeline deps, state machine deps                │
│   contract: file-level header                                │
│   versioned by: format_version (integer), distributed via    │
│                 git release tags on the matcher repo         │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: Engine (RuleCompiler + interpreter)                 │
│   consumes: rulesets matching its declared support           │
│   contract: interpretation semantics, security gates         │
│   versioned by: VERSION constant in RuleEngine               │
│   declares: MAX_SUPPORTED_FORMAT_VERSION                     │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 4: State machine                                       │
│   consumes: IR (state hint + parsed data) from engine        │
│   contract: flow/mode vocabulary, parsed-data schema,        │
│             transition logic, side effects                   │
│   versioned by: API_VERSION constant + vocabulary set        │
└─────────────────────────────────────────────────────────────┘
```

Layers 1, 3, and 4 ship in the APK and are co-versioned with the app build.
Layer 2 ships separately via OTA distribution. The cross-layer contracts are
what make OTA-distributed rulesets (Layer 2) compatible with APK-bundled
implementations (Layers 1, 3, 4).

### Layer 1: Pipeline API version

Each pipeline module declares an `API_VERSION` constant alongside its ID:

```kotlin
object WindowPipeline {
    const val PIPELINE_ID = "accessibility.window"
    const val API_VERSION = 1
}

object ClickPipeline {
    const val PIPELINE_ID = "accessibility.click"
    const val API_VERSION = 1
}

object NotificationPipeline {
    const val PIPELINE_ID = "notification"
    const val API_VERSION = 1
}
```

The app maintains a `PipelineRegistry` that the loader consults:

```kotlin
object PipelineRegistry {
    val pipelines = mapOf(
        WindowPipeline.PIPELINE_ID       to WindowPipeline.API_VERSION,
        ClickPipeline.PIPELINE_ID        to ClickPipeline.API_VERSION,
        NotificationPipeline.PIPELINE_ID to NotificationPipeline.API_VERSION,
    )
}
```

**Bump `API_VERSION` when:**
- The event shape changes (field added, removed, renamed, or semantically redefined)
- Sensitive event filtering becomes more or less aggressive (changes what events rules see)
- Event ordering or debouncing semantics change

**Do not bump for:** internal refactors that don't affect the event surface.

### Layer 2: Ruleset header

The ruleset declares its full set of dependencies at the file root:

```json5
{
  format_version: 2,                     // DSL features used (e.g., state:, parse sub-language)
  min_app_version: "1.0.0",              // semver — app refuses to apply rules requiring a newer version
  platform_id: "doordash.driver",        // which platform this ruleset targets

  pipelines: {                           // Layer 1 deps — which pipelines this ruleset uses
    "accessibility.window": { min_api_version: 1 },
    "accessibility.click":  { min_api_version: 1 },
    "notification":         { min_api_version: 1 },
  },

  state_machine: {                       // Layer 4 deps
    min_api_version: 1,
  },

  screens:       [ /* ScreenRule[] */ ],
  clicks:        [ /* ClickRule[] */ ],
  notifications: [ /* NotificationRule[] */ ],
}
```

Distribution is via tagged releases on the matcher repo (per ADR-0001's
fallback-behavior section). The app's loader walks release tags from newest
to oldest and picks the first one whose declared dependencies are all
satisfied. This means a ruleset that introduces a new pipeline dependency
simply doesn't load on apps that lack that pipeline — they keep running the
previous compatible release.

**Bump `format_version` when:**
- New DSL primitives are added (matchers, parsers, transforms)
- Existing primitives change semantics
- The header schema itself changes (new mandatory fields)

### Layer 3: Engine version

The engine declares what ruleset formats it supports and its own internal
revision:

```kotlin
object RuleEngine {
    const val VERSION = 1
    const val MAX_SUPPORTED_FORMAT_VERSION = 2
}
```

`VERSION` and `MAX_SUPPORTED_FORMAT_VERSION` are independent. An engine
revision bump can change interpretation semantics (e.g., regex compilation
timeout enforcement, transform null-handling, security gate behavior)
without changing what the DSL can express.

**Bump `VERSION` when:**
- Interpretation semantics change in any way that could produce a different
  output for the same ruleset and input
- Security gates tighten (older rules might fail in new ways under stricter
  enforcement)
- Bug fixes that change behavior

**Bump `MAX_SUPPORTED_FORMAT_VERSION` when:**
- The engine gains support for a new `format_version`

The two often bump together but don't have to. Adding support for a new
format_version usually involves engine code changes that warrant a `VERSION`
bump too.

### Layer 4: State machine API version + vocabulary

The state machine declares both an API version and the vocabulary it knows:

```kotlin
object StateMachine {
    const val API_VERSION_MAJOR = 1
    const val API_VERSION_MINOR = 0

    val SUPPORTED_FLOWS: Set<String> = setOf(
        "idle",
        "offer:presented",
        "leg:pickup:navigation",
        "leg:pickup:arrived",
        "leg:dropoff:navigation",
        "leg:dropoff:arrived",
        "post:leg",
    )

    val SUPPORTED_MODES: Set<String> = setOf(
        "offline",
        "online",
        "paused",
    )
}
```

Versioning uses **semver-like semantics** because vocabulary changes have
different impact:

- **Minor bump (1.0 → 1.1):** additive. New flow or mode values added; no
  existing value removed or renamed; no semantic redefinition. Rulesets
  declaring `min_api_version: 1.0` continue to work.
- **Major bump (1.x → 2.0):** breaking. Existing flow or mode value removed,
  renamed, or semantically redefined. Rulesets declaring `min_api_version:
  1.x` are rejected; rulesets must be updated to declare `2.0`.

**Bump minor when:** a new flow or mode value is added to the supported set.

**Bump major when:** an existing value changes meaning, is removed, or is
renamed. (These should be rare; the `mode` × `flow` taxonomy is small enough
to grow additively for most platform additions.)

Side effect handlers are *behind* the state machine API but their addition or
removal doesn't change the rule-side contract — a ruleset doesn't declare
which side effects it depends on. If a side effect's behavior change affects
what rules can rely on (e.g., a tip notification that used to fire no longer
does), that's an `API_VERSION` minor bump because the observable behavior
changed.

---

## The loader's compatibility-check algorithm

At startup or rule-fetch time, the loader runs through these checks **in
order**, rejecting the ruleset on first failure:

```kotlin
fun loadRuleset(json: String): LoadResult {
    val parsed = Json.parseToJsonElement(json).jsonObject

    // 1. Format version: engine support
    val formatVersion = parsed["format_version"]!!.jsonPrimitive.int
    if (formatVersion > RuleEngine.MAX_SUPPORTED_FORMAT_VERSION) {
        return Reject("format_version $formatVersion exceeds supported max ${RuleEngine.MAX_SUPPORTED_FORMAT_VERSION}")
    }

    // 2. App version: ruleset compatibility
    val minAppVersion = parsed["min_app_version"]!!.jsonPrimitive.content
    if (compareVersions(BuildConfig.VERSION_NAME, minAppVersion) < 0) {
        return Reject("ruleset requires app version $minAppVersion, this app is ${BuildConfig.VERSION_NAME}")
    }

    // 3. Platform: enabled by user (per #217)
    val platformId = parsed["platform_id"]!!.jsonPrimitive.content
    if (platformId !in EnabledPlatforms.current()) {
        return Reject("platform $platformId is not enabled")
    }

    // 4. Pipeline dependencies (Layer 1)
    val pipelineDeps = parsed["pipelines"]!!.jsonObject
    for ((pipelineId, dep) in pipelineDeps) {
        val available = PipelineRegistry.pipelines[pipelineId]
            ?: return Reject("unknown pipeline: $pipelineId")
        val required = dep.jsonObject["min_api_version"]!!.jsonPrimitive.int
        if (available < required) {
            return Reject("pipeline $pipelineId requires API >= $required, app provides $available")
        }
    }

    // 5. State machine dependency (Layer 4 — version)
    val smDep = parsed["state_machine"]!!.jsonObject
    val requiredSmMajor = smDep["min_api_version"]!!.jsonPrimitive.int
    if (StateMachine.API_VERSION_MAJOR < requiredSmMajor) {
        return Reject("state_machine requires API >= $requiredSmMajor, app provides ${StateMachine.API_VERSION_MAJOR}")
    }

    // 6. State machine vocabulary (Layer 4 — vocabulary validation)
    for (rule in parsed["screens"]!!.jsonArray) {
        val state = rule.jsonObject["state"]?.jsonObject ?: continue
        state["flow"]?.jsonPrimitive?.content?.let { flow ->
            if (flow !in StateMachine.SUPPORTED_FLOWS) {
                return Reject("rule ${rule.jsonObject["id"]} uses unsupported flow: $flow")
            }
        }
        state["mode"]?.jsonPrimitive?.content?.let { mode ->
            if (mode !in StateMachine.SUPPORTED_MODES) {
                return Reject("rule ${rule.jsonObject["id"]} uses unsupported mode: $mode")
            }
        }
    }

    // 7. Existing compile path
    return Compile(parsed)
}
```

Any rejection falls back to the previously-loaded ruleset (or bundled
defaults if nothing has loaded yet), per ADR-0001's Fallback Behavior
section.

### Vocabulary validation as a compile-time check

Step 6 is the most useful check beyond what's currently planned in #211
Spec 6. Validating that every `state.flow` and `state.mode` value used by
any rule is in the state machine's supported set catches:

- **Typos:** `flow: "leg:pickup:nav"` instead of `"leg:pickup:navigation"`
  rejects at load time with a clear message naming the rule
- **Cross-version drift:** rulesets using flow values added in state machine
  v1.1 cleanly reject on apps still running v1.0
- **Community fork mistakes:** a fork that adds custom flow values without
  bumping `min_api_version` gets caught at load time, not at first-event time

The cost is one tree walk per ruleset load (cheap; one-time at startup).

---

## Replay metadata

For event sourcing (mentioned earlier in design discussions; tracked
informally as data-enrichment scope), every classified event should carry
the layer versions in effect at evaluation time:

```kotlin
data class EventMetadata(
    val engineVersion: Int,
    val rulesetFormatVersion: Int,
    val rulesetReleaseTag: String?,    // null for bundled defaults
    val ruleId: String,                 // which specific rule fired
    val pipelineVersions: Map<String, Int>,
    val stateMachineApiVersion: String,  // "1.0", "1.1", etc.
)
```

This is what makes replay across versions work. Re-running an event-sourced
session through a future engine reveals where divergences happen and
whether they're expected (engine improved, semantics intentionally changed)
or regressions. Without these stamps, divergence is invisible until a user
reports a behavior change in the field.

The metadata storage cost is small (one row of integers + short strings per
event; most events repeat the same values, so column compression handles it).
The forensics value is large.

---

## Bumping discipline summary

| Layer | Version style | Bump when |
|---|---|---|
| **Pipeline API** | Integer per pipeline | Event shape, semantic guarantees, or filter scope changes |
| **Format version** | Integer (file-level) | DSL primitives added, changed, or removed |
| **Engine** | Integer | Interpretation semantics change without DSL changes |
| **Engine MAX_SUPPORTED_FORMAT_VERSION** | Integer | New format_version gains support |
| **State machine API** | semver (major.minor) | minor: additive vocabulary; major: breaking change |

A change that touches multiple layers bumps each independently. For example:
adding a `state:` field with new flow values bumps `format_version`
(new feature in DSL), `engine.VERSION` (new compile path), and
`state_machine.API_VERSION_MINOR` (new flow vocabulary).

---

## Non-goals

- **Not specifying ruleset content versioning** (different file revisions
  of the same `format_version`). That's solved by git release tags on the
  matcher repo. Distinct from `format_version`, which tracks DSL features.
- **Not specifying pipeline-engine compatibility.** Pipelines and engine
  ship in the same APK and are co-versioned with the app build. The engine
  doesn't need to declare which pipeline versions it supports because they
  always match.
- **Not runtime version negotiation.** The loader is one-shot at startup
  (or rule-fetch time). If a ruleset is rejected, fall back to the previous
  ruleset or bundled defaults. No "negotiate down to a compatible subset"
  logic — that's complexity without clear benefit.
- **Not re-versioning ADR-0001 or ADR-0002.** Those documents are immutable
  decision records (with amendments where needed). This ADR adds the
  versioning model on top of what they specified.

---

## Migration

The current shipped state has `format_version: 1` already declared but
unchecked, no other version fields. Migration:

1. **Phase V1: add the constants and registry.** `PipelineRegistry` with
   current pipelines at API version 1; `RuleEngine.VERSION = 1`,
   `MAX_SUPPORTED_FORMAT_VERSION = 1`; `StateMachine.API_VERSION_MAJOR = 1`,
   `MINOR = 0`, with current vocabulary set. Pure code addition; no rule
   file changes yet.

2. **Phase V2: implement the loader checks.** Implements #211 Spec 6 plus
   the additional checks from this ADR. Bundled `rules.default.json` is
   updated to declare full dependencies (`pipelines`, `state_machine`)
   matching the just-defined registry — i.e., declares the minimum it
   actually needs.

3. **Phase V3: bump format_version to 2 when ADR-0002 lands.** When `state:`
   field implementation begins (Phase B1 of ADR-0002), `format_version`
   bumps to 2. Bundled `rules.default.json` updates to use it. Engine's
   `MAX_SUPPORTED_FORMAT_VERSION` bumps to 2 in the same APK release. Older
   APKs continue to work with format_version 1 rulesets (matcher repo's
   release-tag walk picks the latest format_version 1 release for them).

4. **Phase V4: replay metadata.** Adds the `EventMetadata` capture into the
   event-sourcing path. Independent of the loader work; can land any time
   after V1.

V1 + V2 land together (one PR's worth of work, scoped under #211 Spec 6 with
the expanded scope from this ADR). V3 lands with the ADR-0002 implementation
work. V4 lands with whoever picks up event-sourcing enrichment first.

---

## Cross-references

- **ADR-0001** — defines the rule format, including the original
  `format_version` and `min_app_version` fields this ADR builds on
- **ADR-0002** — introduces the state machine layer with flow/mode
  vocabulary; this ADR defines how rulesets declare dependencies on it
- **#87** — original RFC behind ADR-0001
- **#192** — matchers infrastructure epic
- **#211 Spec 6** — implementation issue for loader version checks; scope
  expanded by this ADR to cover all four layers
- **#214** — contributor onboarding (JSON Schema deliverable must cover the
  expanded ruleset header)
- **#217** — accessibility service watched-package configuration; uses
  `EnabledPlatforms` referenced in step 3 of the loader algorithm

---

## Decision summary

Four layers (pipelines, ruleset, engine, state machine) each get a declared
version with explicit bumping discipline. The ruleset header declares its
dependencies on layers 1, 3, and 4. The loader verifies the full
compatibility matrix at startup and falls back to bundled defaults on any
rejection. Vocabulary used in `state:` fields is validated against the state
machine's supported set at load time, catching typos and cross-version drift
before any event is processed. Every classified event records the
versioning context for replay forensics. Distribution is via tagged matcher
repo releases; older apps walk back to the latest compatible release rather
than failing to load anything.
