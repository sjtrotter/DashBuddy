# ADR-0004: Canonical Pipeline Architecture

**Status:** Accepted (implemented in commit `2b71b5e`, 2026-04-30)
**Issue:** Pipeline refactor (pre-work for event sourcing, #193; capture infrastructure)
**Date:** 2026-04-30
**Builds on:** ADR-0003 (versioning and API contracts)

---

## Context

DashBuddy processes three streams of platform events — accessibility window
changes, accessibility click events, and notifications — each through its own
pipeline module. These pipelines grew organically and have divergent internal
structures despite solving the same fundamental problem: receive a raw platform
event, normalize it, decide whether it's worth processing, classify it, and
emit a typed `StateEvent` for the state machine.

Current inconsistencies:

| Concern              | WindowPipeline                                                                    | ClickedPipeline                                                 | NotificationPipeline                             |
|----------------------|-----------------------------------------------------------------------------------|-----------------------------------------------------------------|--------------------------------------------------|
| **Dedup**            | `ScreenDiffer` (structural hash)                                                  | None (each click is distinct)                                   | None (delegated to classifier)                   |
| **Sensitive gate**   | `SensitiveScreenMatcher` runs as a normal matcher at priority 0                   | No gate                                                         | No gate                                          |
| **Side effects**     | `ScreenFactory` does classification + breadcrumbs + snapshot save + odometer read | `ClickFactory` does classification + logging + repository write | `NotificationFactory` wraps into event (trivial) |
| **Capture**          | `SnapshotRepository` (inline, screen-specific)                                    | `ClickLogRepository` (inline, click-specific)                   | None                                             |
| **Domain extension** | `ScreenUpdateEvent`                                                               | `ClickEvent`                                                    | `NotificationEvent`                              |

The problems this creates:

1. **Adding a new pipeline** (clipboard, OCR, GPS fence) requires reinventing
   the same stages. There is no template to follow and no shared contract for
   capture infrastructure to subscribe to.
2. **Debug capture** is wired inline (ScreenFactory calls SnapshotRepository,
   ClickFactory calls ClickLogRepository). Each pipeline has its own ad-hoc
   persistence path with different file layouts, dedup strategies, and quota
   logic. Adding capture to a new pipeline means writing another bespoke
   repository.
3. **The sensitive gate sits at the wrong layer.** `SensitiveScreenMatcher`
   runs as a regular matcher inside `ScreenClassifier`, competing with other
   matchers via the priority system. This means a sensitive screen *is
   classified* (as `Screen.SENSITIVE`) rather than *never reaching the
   classifier*. It also means the full `UiNode` tree of a sensitive screen
   passes through the pipeline and is available to any subscriber — the gate
   is advisory, not structural.
4. **Factory classes conflate transformation with side effects.** ScreenFactory
   does four things (classify, breadcrumb, snapshot, build event).
   ClickFactory does three (classify, log, build event). These should be
   separate pipeline stages, not bundled into a single class.

This ADR defines a canonical stage pattern that all pipeline modules must
follow, the domain contracts that make capture a natural subscriber rather
than inline logic, and the sensitive-gate semantics.

---

## Decision

### The canonical pipeline flow

Every pipeline module follows six stages in fixed order:

```
Source → Transform → Dedup → Sensitive Gate → Classify → Emit
                                                  ↑
                                        Capture subscribes here
```

| Stage              | Responsibility                                                                                                                                                             | Required?         |
|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------|
| **Source**         | Receives raw platform events. Owns the connection to the Android framework (AccessibilityService, NotificationListenerService, etc.).                                      | Yes               |
| **Transform**      | Converts the raw platform type into an immutable domain model (`UiNode`, `RawNotificationData`, etc.). No classification, no side effects.                                 | Yes               |
| **Dedup**          | Drops structurally identical consecutive events. The dedup strategy is pipeline-specific (structural hash for screens, no-op for clicks, content hash for notifications).  | Pipeline-specific |
| **Sensitive Gate** | Drops events from known sensitive screens. Events that fail the gate never reach the classifier and are never emitted to the state machine. See §Sensitive gate semantics. | Pipeline-specific |
| **Classify**       | Determines the event type and extracts structured data. This is where matchers, parsers, and classifiers run. The output is a `(domainModel, classificationResult)` pair.  | Yes               |
| **Emit**           | Produces a typed `StateEvent` for the state machine. Pure mapping, no side effects.                                                                                        | Yes               |

Capture subscribes at the boundary between Classify and Emit. The subscriber
receives the domain model + classification result. For events that were
dropped by the sensitive gate, the subscriber receives a metadata-only record
(classification = `"SENSITIVE"`, no raw content).

#### Why this order?

- **Dedup before Sensitive Gate:** avoids running the gate check on duplicate
  events that would be dropped anyway.
- **Sensitive Gate before Classify:** ensures PII-bearing screens never reach
  matchers, parsers, or capture subscribers with raw content. This is a
  structural guarantee, not a classification result.
- **Classify before Emit:** the classifier output informs both the state event
  and the capture record, so it must run before either.

### Pipeline domain contract

Each pipeline module implements a shared interface that declares its identity
for the versioning system (ADR-0003 Layer 1) and exposes a capture flow:

```kotlin
interface CapturablePipeline {
    /** Stable identifier, e.g. "accessibility.window", "notification". */
    val pipelineId: String

    /** Bumped per ADR-0003 Layer 1 discipline. */
    val apiVersion: Int
}
```

The `pipelineId` and `apiVersion` are the same constants referenced by
ADR-0003's `PipelineRegistry`. This interface formalizes the contract that
ADR-0003 assumed but did not define at the code level.

### CaptureEvent sealed hierarchy

Each pipeline defines a `CaptureEvent` subtype that carries exactly the
data needed for debug capture and regression test generation:

```kotlin
sealed interface CaptureEvent {
    val timestamp: Long
    val classification: String
    val structuralHash: Int
    val contentHash: Int
}

data class ScreenCaptureEvent(
    override val timestamp: Long,
    override val classification: String,
    override val structuralHash: Int,
    override val contentHash: Int,
    val root: UiNode,
) : CaptureEvent

data class ClickCaptureEvent(
    override val timestamp: Long,
    override val classification: String,
    override val structuralHash: Int,
    override val contentHash: Int,
    val clickedNode: UiNode,
    val screenRoot: UiNode?,
    val action: String,
    val rootCaptureLatencyMs: Long,
    val rootCaptureSuccess: Boolean,
) : CaptureEvent

data class NotificationCaptureEvent(
    override val timestamp: Long,
    override val classification: String,
    override val structuralHash: Int,
    override val contentHash: Int,
    val raw: RawNotificationData,
    val type: String,
) : CaptureEvent
```

**Design constraints on CaptureEvent subtypes:**

- Every subtype carries `structuralHash` and `contentHash` so the capture
  service can dedup without knowing the payload shape.
- `classification` is a string (the screen name, click action name, or
  notification type name) so the capture service can organize files by
  category without deserializing the payload.
- Each subtype carries the *full domain model* (not a DTO) — serialization
  to JSON is the capture service's responsibility, not the pipeline's.
- No breadcrumbs or navigation trail. The capture sequence is
  timestamp-ordered; the trail is reconstructable from the sequence itself.

### Capture subscriber pattern

Pipelines do not write files, manage quotas, or handle persistence. They
emit `CaptureEvent`s into a `SharedFlow`. A singleton `CaptureRegistry`
collects all pipeline flows at construction time. A singleton
`PipelineCaptureService` subscribes to the registry and handles:

- **Dedup:** checks the database for an existing record with the same
  `(source, classification, structuralHash)`. If found, updates the
  timestamp (last-seen) and skips the file write.
- **Quota:** 10 files per `(source, classification)` pair, 50 for Unknown.
  Evicts oldest on overflow (LRU).
- **File layout:** `pipeline/<source>/<classification>/<timestamp>__<classification>__<hash>.json`
- **Serialization:** wraps the domain model in a `PipelineCaptureDto` envelope
  that includes `pipelineId`, `pipelineApiVersion`, and
  `captureSchemaVersion` (per ADR-0003 replay metadata guidance).
- **Gating:** compile-time `BuildConfig.DEBUG` check. No capture code
  executes in release builds.

```
pipeline/
├── screen/
│   ├── OFFER_POPUP/
│   │   └── 2026-04-29_19-02-50__OFFER_POPUP__a1b2c3d4.json
│   ├── MAIN_MAP_IDLE/
│   └── UNKNOWN/
├── click/
│   ├── AcceptOffer/
│   ├── DeclineOffer/
│   └── Unknown/
└── notification/
    ├── AdditionalTip/
    ├── NewOrder/
    └── Unknown/
```

This replaces `SnapshotRepository` and `ClickLogRepository` with a single
unified service. Both legacy repositories are deprecated.

### Sensitive gate semantics

The sensitive gate is a **pre-classifier structural filter**, not a matcher.
It determines whether an event should be dropped before it reaches the
classifier. The gate's semantics differ by pipeline:

**Screen pipeline:** The gate checks whether the current screen is a **known
sensitive screen**. "Known" means the screen has been explicitly identified,
catalogued, and added to a maintained set of sensitive screen identifiers.
This is a positive-identification model — a screen is sensitive because we
have determined it to be sensitive, not because it happens to contain a
keyword. The set is maintained alongside the matcher definitions and can be
updated via the same OTA distribution path as rulesets (ADR-0001).

This differs from the current `SensitiveScreenMatcher` implementation, which
uses keyword substring matching against the `UiNode` tree. The keyword
approach has two problems: (1) it can false-positive on screens that happen
to contain a keyword in an unrelated context, and (2) it can false-negative
on sensitive screens that don't contain any keyword in the current set. The
known-screen model is more precise because it's based on explicit human
determination, not heuristic text matching.

When the gate fires, the event is dropped from the pipeline flow — it never
reaches the classifier or the state machine. The capture subscriber receives
a metadata-only record (`classification = "SENSITIVE"`, no `UiNode` payload)
so the capture index can track that a sensitive screen was encountered
without persisting its content.

**Click pipeline:** No sensitive gate. Clicks are node-level events (a
single tapped element), not screen-level. A click on a sensitive screen is
still just "user tapped a button" — the sensitive content is in the
surrounding screen, not the click node. The screen pipeline's gate is the
structural guarantee here.

**Notification pipeline:** No sensitive gate in the initial implementation.
DoorDash driver app notifications do not contain PII (they contain offer
details, tip amounts, and scheduling info). If a future platform's
notifications carry sensitive data, the gate can be added to that platform's
notification pipeline without changing the architecture.

### Factory elimination

The current `ScreenFactory`, `ClickFactory`, and `NotificationFactory`
classes conflate multiple pipeline stages into a single method call. Under
the canonical pattern, each stage is a separate flow operator:

| Factory responsibility | Canonical stage                                         |
|------------------------|---------------------------------------------------------|
| Call classifier        | **Classify** stage (`.map { classifier.identify(it) }`) |
| Build `StateEvent`     | **Emit** stage (`.map { ScreenUpdateEvent(...) }`)      |
| Log breadcrumbs        | Dropped (reconstructable from capture sequence)         |
| Save snapshot          | Replaced by capture subscriber                          |
| Log to repository      | Replaced by capture subscriber                          |
| Read odometer          | **Emit** stage (inline in the `.map`)                   |

All three factory classes are deleted. Their logic moves into flow operators
within the pipeline's `output()` method. This makes each stage visible in
the flow chain and independently testable.

### Dedup strategies per pipeline

| Pipeline         | Dedup strategy                                                                                                                                   | Rationale                                                                                                                                                                       |
|------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Screen**       | `ScreenDiffer` — structural hash of `UiNode` tree (className + viewIdResourceName hierarchy). Drops consecutive events with identical structure. | Screen content changes frequently (timers, counters) but layout changes are the meaningful signal. Structural dedup prevents the classifier from re-running on the same layout. |
| **Click**        | None. Every click is a distinct user action.                                                                                                     | Deduplicating clicks would lose real user intent. Two consecutive taps on the same button are two actions.                                                                      |
| **Notification** | Content hash of `(title, text, bigText)`. Drops notifications with identical text content within a time window.                                  | The DoorDash app occasionally re-posts the same notification. The meaningful signal is the first occurrence.                                                                    |

Each pipeline's dedup stage is implemented as a `.filter()` operator with a
pipeline-specific differ/hasher. Pipelines that don't dedup simply omit the
stage.

---

## Applying the pattern: three pipelines

### Screen (WindowPipeline)

```kotlin
class WindowPipeline @Inject constructor(
    private val contentChangedPipeline: ContentChangedPipeline,
    private val stateChangedPipeline: StateChangedPipeline,
    private val differ: ScreenDiffer,
    private val sensitiveGate: SensitiveScreenGate,
    private val classifier: ScreenClassifier,
    private val odometerRepository: OdometerRepository,
    private val captureRegistry: CaptureRegistry,
) : CapturablePipeline {
    override val pipelineId = "accessibility.window"
    override val apiVersion = 1

    private val _captures = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 32)
    init { captureRegistry.register(this, _captures) }

    fun output(): Flow<StateEvent> = merge(
        contentChangedPipeline.output(),        // Source + Transform
        stateChangedPipeline.output(),
    )
    .filter { differ.hasChanged(it) }           // Dedup
    .filter { !sensitiveGate.isSensitive(it) }  // Sensitive Gate
    .map { it to classifier.identify(it) }      // Classify
    .onEach { (node, info) ->                   // Capture point
        if (BuildConfig.DEBUG) {
            _captures.tryEmit(ScreenCaptureEvent(...))
        }
    }
    .map { (_, info) ->                         // Emit
        ScreenUpdateEvent(
            timestamp = System.currentTimeMillis(),
            screenInfo = info,
            odometer = odometerRepository.getCurrentMiles(),
        )
    }
}
```

### Click (ClickedPipeline)

```kotlin
class ClickedPipeline @Inject constructor(
    private val source: AccessibilitySource,
    private val classifier: ClickClassifier,
    private val captureRegistry: CaptureRegistry,
) : CapturablePipeline {
    override val pipelineId = "accessibility.click"
    override val apiVersion = 1

    private val _captures = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 32)
    init { captureRegistry.register(this, _captures) }

    fun output(): Flow<StateEvent> = source.events
        .filter { it.eventType == TYPE_VIEW_CLICKED }      // Source
        .filter { it.packageName == DOORDASH_PACKAGE }
        .mapNotNull { it.source?.toUiNode() }              // Transform
        // No dedup
        // No sensitive gate
        .map { it to classifier.classify(it) }             // Classify
        .onEach { (node, info) ->                          // Capture point
            if (BuildConfig.DEBUG) {
                val start = System.nanoTime()
                val root = source.getCurrentRootNode()
                val latencyMs = (System.nanoTime() - start) / 1_000_000L
                _captures.tryEmit(ClickCaptureEvent(
                    clickedNode = node,
                    screenRoot = root,
                    rootCaptureLatencyMs = latencyMs,
                    rootCaptureSuccess = root != null,
                    ...
                ))
            }
        }
        .map { (node, info) ->                             // Emit
            ClickEvent(timestamp = System.currentTimeMillis(), info = info, sourceNode = node)
        }
}
```

Note: the click capture point attempts to grab the current screen root at
capture time. This is the **root capture experiment** — the `screenRoot`
field and `rootCaptureLatencyMs` / `rootCaptureSuccess` fields let us
measure whether capturing the full screen context at click time is feasible
in production (latency budget: < 50ms to avoid blocking the flow).

### Notification (NotificationPipeline)

```kotlin
class NotificationPipeline @Inject constructor(
    private val source: NotificationSource,
    private val filter: NotificationFilter,
    private val classifier: NotificationClassifier,
    private val captureRegistry: CaptureRegistry,
) : CapturablePipeline {
    override val pipelineId = "notification"
    override val apiVersion = 1

    private val _captures = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 32)
    init { captureRegistry.register(this, _captures) }

    fun output(): Flow<StateEvent> = source.events
        .mapNotNull { it.toDomain() }                     // Transform
        .filter { filter.isRelevant(it) }                 // Filter (package + clearable)
        // No sensitive gate
        .map { it to classifier.classify(it) }            // Classify
        .onEach { (raw, info) ->                          // Capture point
            if (BuildConfig.DEBUG) {
                _captures.tryEmit(NotificationCaptureEvent(...))
            }
        }
        .map { (raw, info) ->                             // Emit
            NotificationEvent(timestamp = raw.postTime, info = info)
        }
}
```

---

## Serialization envelope

Each captured JSON file wraps the domain payload in an envelope that carries
the versioning metadata from ADR-0003:

```json
{
  "pipelineId": "accessibility.window",
  "pipelineApiVersion": 1,
  "captureSchemaVersion": 1,
  "source": "screen",
  "classification": "OFFER_POPUP",
  "timestamp": 1714420970000,
  "timestampReadable": "2026-04-29T19:02:50.000",
  "structuralHash": 1234567890,
  "contentHash": -987654321,
  "payload": {
    "type": "screen",
    "root": { /* UiNode tree */ }
  }
}
```

The `payload` field is type-discriminated (`"type": "screen"` | `"click"` |
`"notification"`). The envelope fields are stable across all pipeline types;
only the payload shape varies. `captureSchemaVersion` tracks changes to the
envelope format itself (independent of `pipelineApiVersion`, which tracks
the event shape).

---

## Non-goals

- **Not defining the database schema.** The `PipelineCaptureRecord` entity
  and DAO are implementation details. This ADR defines the conceptual model
  (dedup by hash, quota per category, LRU eviction) but not the Room schema.
- **Not specifying the CaptureRegistry API.** The registry is a simple
  collection; the registration mechanism (constructor injection, init block,
  explicit call) is an implementation choice.
- **Not standardizing sub-pipelines.** `ContentChangedPipeline` and
  `StateChangedPipeline` are internal to the screen pipeline. They handle
  debouncing and root-node capture, which are accessibility-framework-specific
  concerns. This ADR standardizes the stages *after* the raw event becomes a
  domain model, not the platform-specific plumbing that produces it.
- **Not replacing the JSON DSL matchers.** The Classify stage calls the
  existing `ScreenClassifier` / `ClickClassifier` / `NotificationClassifier`.
  Those classifiers internally run the Kotlin matchers and (in debug) the
  JSON rule interpreter dual-run. This ADR doesn't change classification
  logic, only where it sits in the pipeline flow.
- **Not defining the sensitive screen set.** The gate's contract is "check
  against a maintained set of known sensitive screens." The contents of that
  set, and the process for adding to it, are outside this ADR's scope.

---

## Migration

### Phase 0: Notification bug fixes (standalone, no architectural change)

**0a.** Fix empty text extraction in `NotificationMapper`: `Bundle.getString()`
→ `Bundle.getCharSequence(key)?.toString()` for title, text, bigText.

**0b.** Filter foreground service spam in `NotificationFilter`: add
`&& raw.isClearable`. The DoorDash foreground service notification
(`isClearable = false`, fires ~1/sec) is noise.

These are bug fixes that land independently of the refactor.

### Phase 1: Domain contracts

Add `CapturablePipeline` interface and `CaptureEvent` sealed hierarchy to
`:domain`. Pure additions, no existing code changes. Tests can verify the
sealed hierarchy is exhaustive.

### Phase 2: Database + serialization

Add `PipelineCaptureRecord` entity, `PipelineCaptureDao`, and the
`PipelineCaptureDto` / `CapturePayloadDto` serialization DTOs. Database
version bump. Migration adds the new table; existing tables unchanged.

### Phase 3: Capture infrastructure

Add `CaptureRegistry` and `PipelineCaptureService` to `:core:data`. Wire
`PipelineCaptureService.start()` into the accessibility service lifecycle.
At this point, no pipelines emit captures yet — the service subscribes but
receives nothing.

### Phase 4: Pipeline refactoring (the core work)

Refactor each pipeline to the canonical flow, one at a time:

1. **WindowPipeline** — extract `SensitiveScreenGate`, inline factory logic
   into flow operators, emit `ScreenCaptureEvent`.
2. **ClickedPipeline** — inline factory logic, add root capture experiment,
   emit `ClickCaptureEvent`.
3. **NotificationPipeline** — inline factory logic, emit
   `NotificationCaptureEvent`.

Each pipeline refactor is independently testable. The factory classes are
deleted after their pipeline is migrated.

### Phase 5: Cleanup

Deprecate `SnapshotRepository` and `ClickLogRepository`. Delete factory
classes. Evaluate breadcrumb usage and remove if nothing else depends on it.

---

## Cross-references

- **ADR-0001** — rule format; the matchers that the Classify stage calls
- **ADR-0002** — state taxonomy; the vocabulary that classified events
  carry into the state machine
- **ADR-0003** — versioning; `CapturablePipeline.pipelineId` and
  `apiVersion` are the Layer 1 constants defined there; capture envelope
  includes ADR-0003 replay metadata
- **#192** — matchers infrastructure epic
- **#193** — aggregation RFC (capture infrastructure is pre-work for
  event sourcing)

---

## Decision summary

All pipeline modules follow a six-stage canonical flow: Source → Transform →
Dedup → Sensitive Gate → Classify → Emit. Each pipeline implements
`CapturablePipeline` and emits typed `CaptureEvent` subtypes through a
`SharedFlow`. A singleton `PipelineCaptureService` subscribes to all
pipelines via a `CaptureRegistry` and handles dedup, quota, and file
persistence uniformly. The sensitive gate is a structural pre-filter that
drops events from known sensitive screens before they reach the classifier
— it checks against a maintained set of identified sensitive screens, not
keyword heuristics. Factory classes are eliminated; their responsibilities
are distributed across the canonical stages as flow operators. Capture
files carry ADR-0003 versioning metadata in a stable envelope format for
replay forensics.
