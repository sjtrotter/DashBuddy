# ADR-0005: Pipeline-Driven, Multi-Region State Architecture

**Status:** Accepted
**Date:** 2026-05-01
**Builds on:** ADR-0001, ADR-0002, ADR-0003, ADR-0004
**Supersedes:** Current `AppStateV2` sealed hierarchy, `ScreenInfo` sealed hierarchy,
`ScreenFactory`/`ClickFactory`/`NotificationFactory`, per-state reducers, state factories,
`SnapshotRepository`, `ClickLogRepository`, `StateRecoveryRepository`

---

## Context

DashBuddy's pipeline and state machine have grown organically. The accessibility
pipelines (`WindowPipeline`, `ClickedPipeline`, `NotificationPipeline`) each have
their own ad-hoc shape with kitchen-sink factory classes that mix classification,
snapshotting, breadcrumb and odometer enrichment. The state machine uses a 10-class
`AppStateV2` sealed hierarchy with 9 per-state reducers and 8 state factories, all
tightly coupled to DoorDash-specific `ScreenInfo` subtypes.

Three accepted ADRs define a future direction:

- **ADR-0001** — matchers/parsers as JSON rulesets, OTA-distributed, compiled-once
  lambda hot path. `RuleCompiler` and `JsonRuleInterpreter` already exist;
  `rules.default.json` ships in app assets.
- **ADR-0002** — rules emit platform-agnostic IR (`flow` + `modeHint` +
  `parsedFields`); state machine consumes IR, never sees platform-specific screen
  names. Amendment: mode is inferred, not declared.
- **ADR-0003** — four-layer versioning (Pipeline / Ruleset / Engine / State Machine)
  with loader compatibility checks.

This ADR unifies those directions into a single concrete architecture and migration
plan. It is the authoritative implementation spec.

### Vocabulary

These terms replace platform-locked language throughout the codebase:

| Term        | Replaces | Definition                                                                                                                                         |
|-------------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| **Task**    | Leg      | A single segment: one location, either navigation or arrival activity. A pickup is one Task; a dropoff is one Task.                                |
| **Job**     | (new)    | Accumulation of related Tasks. DoorDash batched delivery = 1 Job (2-3 pickup Tasks + 2-3 dropoff Tasks). Uber ride = 1 Job (1 pickup + 1 dropoff). |
| **Session** | Dash     | A continuous period of being online on one platform. `dashId` becomes `sessionId`.                                                                 |

### Approach

- **Big-bang migration.** Replace `AppStateV2` and `ScreenInfo` outright. No
  translation layers, no dual-run safety nets. This is alpha software with one user.
- **Multi-region statechart with healing.** Not a single linear estimator. Parallel
  regions, each with own state and transition logic.
- **Disk-only capture.** No Room rows for capture artifacts. Self-contained JSON
  envelopes on disk.
- **No `target:` field.** Rule `id` (e.g., `doordash.screen.offer`) is the sole
  identifier. Kotlin loses `Screen`, `ClickInfo`, `NotificationInfo` enums.

---

## 1. Layer Model

Five layers, each with a single responsibility and one direction of dependency.

```
Layer 0 — Sources              AccessibilitySource, NotificationSource
                               Contract: MutableSharedFlow<RawPayload>; emit-only.
                               Future: ClipboardSource, OcrSource, GpsWaypointSource

Layer 1 — Pipelines            Pipeline<RAW, OUT> with composable sub-pipes.
                               Each Pipeline produces Observation objects.
                               ADR-0003 Layer 1 — pipelineId + apiVersion.
                               Capture taps go through CaptureBus.

Layer 2 — Engine               RuleCompiler + JsonRuleInterpreter.
                               Classifies raw payloads against compiled rulesets.
                               Emits FlowObservation IR (with ruleId provenance).
                               ADR-0003 Layer 3 — RuleEngine.VERSION.

Layer 3 — State Machine        Multi-region statechart (Flow / Platform / CrossPlatform).
                               Each region steps independently per observation.
                               Healing logic resolves implausible transitions.
                               Persists observation log + AppState snapshots.
                               Dispatches AppEffects on per-region transition diffs.
                               ADR-0003 Layer 4 — StateMachine.API_VERSION.

Layer 4 — Presentation         Compose HUD, Bubble, side-effect handlers,
                               Room/DataStore sinks. Reads from regions.
```

The current `ScreenFactory` violates this layering — it mixes Layer 1 (build
ScreenUpdateEvent), Layer 2 (call ScreenClassifier), and Layer 4 (snapshot to disk,
read odometer, append breadcrumbs). It is eliminated.

---

## 2. Pipeline Interface (Layer 1)

### 2.1 Core interfaces

```kotlin
// :domain
interface Pipeline<RAW, OUT : Observation> {
    val pipelineId: String       // "accessibility.window", "notification"
    val apiVersion: Int          // ADR-0003 Layer 1
    fun output(): Flow<OUT>
}

interface CapturablePipeline<RAW, OUT : Observation> : Pipeline<RAW, OUT> {
    val captureSchema: CaptureSchema<RAW>
}

abstract class CompositePipeline<OUT : Observation>(
    private val children: List<Pipeline<*, OUT>>,
) : Pipeline<Nothing, OUT> {
    final override fun output(): Flow<OUT> =
        merge(*children.map { it.output() }.toTypedArray())
}
```

### 2.2 Concrete pipelines

`AccessibilityPipeline` becomes a `CompositePipeline` with sub-pipes:

```kotlin
@Singleton
class AccessibilityPipeline @Inject constructor(
    window: WindowSubPipe,
    click: ClickSubPipe,
    longClick: LongClickSubPipe,   // scaffold initially
    scroll: ScrollSubPipe,         // scaffold initially
    focus: FocusSubPipe,           // scaffold initially
) : CompositePipeline<Observation>(listOf(window, click, longClick, scroll, focus)) {
    override val pipelineId = "accessibility"
    override val apiVersion = 1
}
```

### 2.3 WindowSubPipe — canonical example

The pipeline composes classification (Layer 2) and capture in the right order.
Classification runs first; capture receives the `ruleId` from classification.
Neither classifier nor capture knows the other exists.

```kotlin
@Singleton
class WindowSubPipe @Inject constructor(
    private val source: AccessibilitySource,
    private val differ: ScreenDiffer,
    private val sensitiveGate: SensitiveGate,
    private val classifier: ScreenClassifier,    // Layer 2
    private val captureBus: CaptureBus,
) : CapturablePipeline<UiNode, Observation.Screen> {
    override val pipelineId = "accessibility.window"
    override val apiVersion = 1
    override val captureSchema = CaptureSchema.UiTree

    override fun output(): Flow<Observation.Screen> = merge(
            ContentChangedSource(source).asUiTrees(),
            StateChangedSource(source).asUiTrees(),
        )
        .filter(differ::hasChanged)
        .filter { node -> !sensitiveGate.isSensitive(node) }
        .map { tree ->
            val classification = classifier.classify(tree)
            captureBus.offer(
                pipelineId = pipelineId,
                schema = CaptureSchema.UiTree,
                payload = tree,
                ruleId = classification.ruleId,
            )
            classification.toObservation()
        }
}
```

### 2.4 PipelineV2

Becomes a thin Hilt multibind consumer. Adding a new pipeline = implement
`Pipeline`, bind into the Hilt set, register in `PipelineRegistry`.

```kotlin
@Singleton
class PipelineV2 @Inject constructor(
    pipelines: Set<@JvmSuppressWildcards Pipeline<*, Observation>>,
) {
    val events: Flow<Observation> =
        merge(*pipelines.map { it.output() }.toTypedArray())
            .flowOn(Dispatchers.Default)
}
```

---

## 3. Capture System (Layer 1 cross-cutting)

Disk-only. No Room rows for captures. Each capture is a self-describing JSON envelope
portable to the matchers repo as test corpus.

### 3.1 Schema

```kotlin
sealed interface CaptureSchema<T> {
    val schemaId: String                    // "accessibility.uitree.v1"
    fun serialize(payload: T): String
    fun deserialize(json: String): T

    object UiTree          : CaptureSchema<UiNode>
    object Click           : CaptureSchema<ClickPayload>
    object RawNotification : CaptureSchema<RawNotificationData>
}

data class CaptureEnvelope<T>(
    val captureId: String,             // hash-based deterministic id
    val pipelineId: String,
    val schemaId: String,
    val timestamp: Long,
    val platform: String,              // "doordash", "uber", "_unknown"
    val ruleId: String?,               // matched rule id, null if no match
    val classificationName: String?,   // name segment of ruleId for path use
    val metadata: ReplayMetadata,
    val payload: T,
)

data class ReplayMetadata(
    val engineVersion: Int,
    val rulesetFormatVersion: Int?,
    val rulesetReleaseTag: String?,
    val rulesetSignature: String?,
    val pipelineVersions: Map<String, Int>,
    val stateMachineApiVersion: String,
    val appVersion: String,
    val deviceFingerprint: String?,
)
```

### 3.2 CaptureBus / CaptureService

```kotlin
interface CaptureBus {
    fun <T> offer(
        pipelineId: String,
        schema: CaptureSchema<T>,
        payload: T,
        ruleId: String?,
    )
}

@Singleton
class CaptureService @Inject constructor(
    private val captureFileStore: CaptureFileStore,
    private val metadataProvider: ReplayMetadataProvider,
    private val rulesetCatalog: RulesetCatalog,
    private val devSettings: DevSettings,
) : CaptureBus
```

### 3.3 Disk layout

Platform groups at top. Filenames fully qualified so they're meaningful in any
file browser.

```
captures/
├── doordash/
│   ├── accessibility.window/
│   │   ├── offer/
│   │   │   └── 1714534200123__doordash__offer__a3f9c2.json
│   │   ├── on_pickup/
│   │   └── _unknown/
│   ├── accessibility.click/
│   │   ├── accept_offer/
│   │   └── _unknown/
│   └── notification/
│       ├── additional_tip/
│       └── _unknown/
├── uber/
│   └── ...
└── _unknown/
    └── accessibility.window/
        └── _unknown/
```

Filename: `{timestamp_ms}__{platform}__{classification_name}__{content_hash_6}.json`

`platform` and `classification_name` derived from `ruleId` by splitting on `.`:

- `doordash.screen.offer` → platform=`doordash`, name=`offer`
- `doordash.click.accept_offer` → platform=`doordash`, name=`accept_offer`
- No match: platform=`_unknown`, name=`_unknown`

### 3.4 Quotas

- Per `(platform, pipelineId, classification)` LRU cap: 10 each, 50 for `_unknown`.
- Disk retention TTL: 7 days clicks, 14 days windows.
- `DevSettings.captureMode`: `Off` | `OnlyUnknown` | `All`. Release builds force `Off`.

### 3.5 Replaces

`SnapshotRepository` (`core/data/.../log/SnapshotRepository.kt`),
`ClickLogRepository` (`core/data/.../log/ClickLogRepository.kt`), and
`Breadcrumbs` are deleted. Breadcrumbs' role is reconstructable from the
observation log (Section 7).

---

## 4. Observation Contracts (Layer 2 → Layer 3 IR)

Replace the 12-subtype `ScreenInfo` with a flat `Observation` sealed interface
keyed on platform-agnostic `flow`. The IR is what rules produce and what the
state machine consumes.

### 4.1 Observation hierarchy

```kotlin
sealed interface Observation : StateEvent {
    override val timestamp: Long
    val captureId: String?
    val ruleId: String?            // platform-qualified rule id (sole identifier)
    val metadata: ReplayMetadata

    sealed interface FlowObservation : Observation {
        val flow: Flow?            // null = no flow contribution
        val modeHint: Mode?        // hint only, never authoritative
        val parsed: ParsedFields
    }

    data class Screen(
        override val timestamp: Long,
        override val captureId: String?,
        override val ruleId: String?,
        override val metadata: ReplayMetadata,
        override val flow: Flow?,
        override val modeHint: Mode?,
        override val parsed: ParsedFields,
    ) : FlowObservation

    data class Click(...) : FlowObservation
    data class Notification(...) : FlowObservation

    data class Timeout(override val timestamp: Long, val type: TimeoutType, ...) : Observation
    data class UiInput(...) : Observation
    data class Loopback(...) : Observation    // SideEffectEngine → state machine
}
```

The `platform` for any observation is derived from `ruleId?.substringBefore(".")`.

### 4.2 The `target:` field is removed

Rule `id` is the sole identifier. `Screen`, `ClickInfo`, `NotificationInfo`
Kotlin enums are deleted. The `RuleCompiler` drops `target:` parsing. After this:

```json5
{
  "id": "doordash.screen.offer",
  "match": { ... },
  "state": { "flow": "offer:presented", "modeHint": "online" },
  "parse": { "fields": { ... } }
}
```

The state machine never inspects the `id` — it dispatches on
`flow`/`modeHint`/`parsed`. The `id` flows to capture path + observation log +
telemetry.

### 4.3 Flow / Mode vocabulary

```kotlin
enum class Flow(val wire: String) {
    Idle("idle"),
    OfferPresented("offer:presented"),
    TaskPickupNavigation("task:pickup:navigation"),
    TaskPickupArrived("task:pickup:arrived"),
    TaskDropoffNavigation("task:dropoff:navigation"),
    TaskDropoffArrived("task:dropoff:arrived"),
    PostTask("post:task"),
    SessionEnded("session:ended"),
}

enum class Mode { Offline, Online, Paused }
```

`Mode.Paused` covers the paused-session screen — no `Flow.SessionPaused`.
Per ADR-0002 amendment, paused is a mode signal, not a flow.

### 4.4 ParsedFields

Single `TaskFields` with `phase`/`subFlow` discriminators replaces separate
pickup/dropoff field types. All four `task:*:*` flows produce `TaskFields`.

```kotlin
sealed class ParsedFields {
    abstract val activity: String?       // free-typed platform tag

    data object None : ParsedFields() { override val activity = null }

    data class IdleFields(
        override val activity: String? = null,
        val zoneName: String? = null,
        val sessionType: SessionType? = null,  // per_offer, by_time
        val sessionPay: Double? = null,
        val waitTimeEstimate: String? = null,
        val isHeadingBackToZone: Boolean = false,
        val spotSaveDeadline: Long? = null,
    ) : ParsedFields()

    data class OfferFields(
        override val activity: String? = null,
        val parsedOffer: ParsedOffer,    // existing 13-field type, kept as-is
    ) : ParsedFields()

    data class TaskFields(
        override val activity: String? = null,   // "shopping", "scanning_card"
        val phase: TaskPhase,                    // PICKUP | DROPOFF
        val subFlow: TaskSubFlow,                // NAVIGATION | ARRIVED
        val storeName: String? = null,
        val customerNameHash: String? = null,
        val customerAddressHash: String? = null,
        val deadline: ParsedTime? = null,
        val itemCount: Int? = null,
        val redCardTotal: Double? = null,
        val arrivalConfirmed: Boolean = false,
    ) : ParsedFields()

    data class PostTaskFields(
        override val activity: String? = null,
        val totalPay: Double,                    // REQUIRED
        val payBreakdown: PayBreakdown? = null,
        val isExpanded: Boolean = false,
        val expandButtonId: String? = null,
        val sessionEarnings: Double? = null,
        val offersAccepted: Int? = null,
        val offersTotal: Int? = null,
    ) : ParsedFields()

    data class SessionEndedFields(
        override val activity: String? = null,
        val totalEarnings: Double,               // REQUIRED
        val sessionDurationMillis: Long? = null,
        val offersAccepted: Int? = null,
        val offersTotal: Int? = null,
        val weeklyEarnings: Double? = null,
    ) : ParsedFields()

    data class PausedFields(
        override val activity: String? = null,
        val remainingText: String,               // REQUIRED — "34:15"
        val remainingMillis: Long,               // REQUIRED — for countdown ticker
    ) : ParsedFields()

    data class TimelineFields(
        override val activity: String? = null,
        val sessionEarnings: Double? = null,
        val offerEarnings: Double? = null,
        val endsAtText: String? = null,
        val endsAtMillis: Long? = null,
        val tasks: List<TimelineTaskFields> = emptyList(),
    ) : ParsedFields()

    data class RatingsFields(
        override val activity: String? = null,
        val acceptanceRate: Double? = null,
        val completionRate: Double? = null,
        val onTimeRate: Double? = null,
        val customerRating: Double? = null,
        val deliveriesLast30Days: Int? = null,
        val lifetimeDeliveries: Int? = null,
    ) : ParsedFields()

    data class SensitiveFields(
        override val activity: String? = null,
    ) : ParsedFields()

    data class ClickFields(
        override val activity: String? = null,
        val intent: String,              // "accept_offer", "decline_offer", etc.
        val nodeId: String? = null,
        val nodeText: String? = null,
    ) : ParsedFields()
}

enum class TaskPhase { PICKUP, DROPOFF }
enum class TaskSubFlow { NAVIGATION, ARRIVED }
```

### 4.5 Contract validation

`FieldsFactory.create(flow, raw: Map<String, Any?>)` validates rule output at
load time. If a rule declares `flow: "offer:presented"` but doesn't produce
`payAmount`, the factory throws `RuleCompileException` and the rule is rejected
before any event is processed.

```kotlin
object FieldsFactory {
    fun create(flow: Flow, raw: Map<String, Any?>): ParsedFields = when (flow) {
        Flow.OfferPresented -> OfferFields(
            parsedOffer = ParsedOffer(
                payAmount = raw.requireDouble("payAmount"),
                offerHash = raw.requireString("offerHash"),
                distanceMiles = raw.optDouble("distanceMiles"),
                // ...
            )
        )
        Flow.TaskPickupNavigation,
        Flow.TaskPickupArrived -> TaskFields(
            phase = TaskPhase.PICKUP,
            subFlow = if (flow == Flow.TaskPickupArrived) TaskSubFlow.ARRIVED
                      else TaskSubFlow.NAVIGATION,
            storeName = raw.optString("storeName"),
            // ...
        )
        // ... other flows
    }
}
```

### 4.6 Offer store hints

The offer's listed store names are **hints**, not sources of truth. When an
offer is accepted, the store names from the offer provide an `offerStoreHint`
for the Job. The actual authoritative store name comes from the Task observation
when the driver enters `task:pickup:navigation`. The store name may differ
(get more specific, change on reassignment, etc.).

```kotlin
data class Job(
    val jobId: String,
    val offerStoreHint: List<String>,   // from ParsedOffer.orders[*].storeName
    val parentOfferHash: String?,       // null if recovered without seeing offer
    val tasks: List<Task>,
    // ...
)
```

---

## 5. AppState — Multi-Region Structure (Layer 3)

The state machine is a Harel-style statechart with parallel regions. Each region
has its own current state and transition logic. Regions update independently per
observation.

### 5.1 Region architecture

```
                    Region 2 (DoorDash)
                  /
Region 0 → Region 1 ← Region 3 (Uber)
                  \
                    Region 4 (Instacart)
                    ...
```

| Region                       | Role                                                       | Owns                                                                                                               |
|------------------------------|------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| **Region 0 — Flow**          | Ground-truth screen interpretation; declarative from rules | `flow`, `pendingOffer`, `activePlatform`, `sourceRuleId`                                                           |
| **Region 1 — CrossPlatform** | Aggregator across all platform regions; derived, read-only | totals (earnings/miles/jobs/deliveries), session count, "any platform online"                                      |
| **Region 2+ — Platform**     | One per platform; durable per-platform state               | `mode`, `session`, `activeJob`, `activeTask`, `recentTasks`, `confidence`, platform-context (zone, ratings, surge) |

### 5.2 Data flow direction

Strictly downstream-then-aggregate: **0 → 2+ → 1**.

1. Observation arrives → Region 0 steps first.
2. Region 0 → Region 2+: observation routes to platform region matching
   `ruleId.platform`. That region steps using observation + Region 0's new flow.
3. Regions 2+ → Region 1: after platform regions step, Region 1 reads
   post-step platform snapshots and recomputes aggregates.

No region writes into another. The two-way feel comes from the UI reading any
region at render time, not from regions pushing data to each other.

### 5.3 AppState

```kotlin
data class AppState(
    val regions: Regions,
    val timestamp: Long = System.currentTimeMillis(),
    val correlationVersion: Long = 0L,
)

data class Regions(
    val flow: FlowRegion = FlowRegion(),                          // R0
    val crossPlatform: CrossPlatformRegion = CrossPlatformRegion(),   // R1
    val platforms: Map<Platform, PlatformRegion> = emptyMap(),    // R2+
)
```

### 5.4 Region 0 — FlowRegion

Reflects what we see the worker looking at right now. Updates fast. Transient.
Owns offer presentation since offers are screen-bound and ephemeral.

```kotlin
data class FlowRegion(
    val flow: Flow = Flow.Idle,
    val pendingOffer: PendingOffer? = null,
    val sourceRuleId: String? = null,
    val activePlatform: Platform? = null,    // derived from latest ruleId
    val lastObservedAt: Long = 0,
)

data class PendingOffer(
    val offerHash: String,
    val offerFields: OfferFields,
    val presentedAt: Long,
    val evaluation: OfferEvaluation? = null,   // filled async by evaluator
    val returnFlow: Flow,                       // flow to return to on decline/timeout
)
```

Flow region transitions on every accepted `FlowObservation`. No plausibility
gating — whatever the rules say we're seeing, we believe. Implausibility is
handled at Region 2+.

`OfferEvaluator` runs when `pendingOffer` is set; evaluation result is attached.
When accept/decline click is observed, flow transitions out of `OfferPresented`,
and Region 2+ reads the disposition.

### 5.5 Region 2+ — PlatformRegion

Per-platform durable state. Mode lives HERE, not globally.

```kotlin
data class PlatformRegion(
    val platform: Platform,
    val mode: Mode = Mode.Offline,
    val session: Session? = null,
    val activeJob: Job? = null,
    val activeTask: Task? = null,
    val recentTasks: List<Task> = emptyList(),
    val confidence: ModeConfidence = ModeConfidence.empty(),
    // platform-specific context
    val zoneName: String? = null,
    val sessionType: SessionType? = null,
    val ratings: RatingsSnapshot? = null,
    val surgeMultiplier: Double? = null,
    val lastObservedAt: Long = 0,
)

data class ModeConfidence(
    val pendingMode: Mode? = null,
    val pendingFlow: Flow? = null,
    val supportingObservations: Int = 0,
    val firstSeenAt: Long? = null,
) {
    companion object { fun empty() = ModeConfidence() }
}
```

Mode region transitions on:

1. **Mode-defining observations** — explicit signals like `session:ended`,
   online/offline toggles. Immediate transition.
2. **Plausibly inferred** — flow change implies mode change AND the transition
   is plausible (e.g., `OfferPresented → TaskPickupNavigation` while
   mode=Online, no active job → start new Job + Task; immediate).
3. **Implausibly inferred (heal path)** — flow strongly implies a different
   mode but the transition is implausible (e.g., observe `TaskPickupArrived`
   while mode=Offline). Increment confidence; if threshold reached, force
   transition (heal). Default threshold: 2 supporting observations within 10s,
   OR 1 high-weight signal.

### 5.6 Session / Job / Task

```kotlin
data class Session(
    val sessionId: String,
    val startedAt: Long,
    val earningMode: SessionType? = null,
    val runningEarnings: Double = 0.0,
    val runningMiles: Double = 0.0,
)

data class Job(
    val jobId: String,
    val offerStoreHint: List<String>,
    val parentOfferHash: String?,        // null if recovered without seeing offer
    val tasks: MutableList<Task> = mutableListOf(),
    val startedAt: Long,
)

data class Task(
    val taskId: String,
    val jobId: String,
    val phase: TaskPhase,
    val storeName: String? = null,
    val customerNameHash: String? = null,
    val customerAddressHash: String? = null,
    val deadlineMillis: Long? = null,
    val activity: String? = null,
    val itemCount: Int? = null,
    val redCardTotal: Double? = null,
    val arrivedAt: Long? = null,            // set once, never overwritten
    val odometerAtEntry: Double? = null,
    val odometerAtArrival: Double? = null,
    val startedAt: Long,
    val completedAt: Long? = null,
    val recovered: Boolean = false,         // true if synthesized by healing
)
```

### 5.7 Region 1 — CrossPlatformRegion

Derived, read-only from platform regions. Recomputed after Regions 2+ step.

```kotlin
data class CrossPlatformRegion(
    val anyPlatformOnline: Boolean = false,
    val activeSessionCount: Int = 0,
    val totalsToday: PeriodTotals = PeriodTotals.empty(),
    val totalsThisWeek: PeriodTotals = PeriodTotals.empty(),
    val totalsLifetime: PeriodTotals = PeriodTotals.empty(),
    val mostRecentActivityAt: Long = 0,
    val mostRecentActivityPlatform: Platform? = null,
)

data class PeriodTotals(
    val earnings: Double = 0.0,
    val miles: Double = 0.0,
    val deliveries: Int = 0,
    val jobs: Int = 0,
    val onlineDuration: Long = 0L,
) {
    companion object { fun empty() = PeriodTotals() }
}
```

### 5.8 Healing examples

**Back-gesture flicker.** User does back-gesture mid-pickup; app shows idle map
briefly before snapping back to pickup.

- t0: R0 = TaskPickupArrived; R2(DD) = Online + activeTask
- t1: R0 = Idle (one observation); R2(DD) sees flow change; idle is implausible
  (no pickup-completed signal); confidence: pendingMode=Online-Idle, count=1
- t2: R0 = TaskPickupArrived again; confidence resets; stays Online. activeTask
  preserved across flicker.

**Observed pickup without preceding offer.** App started while worker was
mid-pickup.

- t0: R0 = TaskPickupArrived; R2(DD) = Offline (default startup)
- R2(DD): flow implies Online + activeJob+Task, but Offline→Online is
  implausible. Confidence: pendingMode=Online, count=1
- t1: Same flow re-observed. Confidence count=2 → threshold hit. Heal:
  synthesize Job + Task with `recovered = true`, transition R2(DD) to Online +
  activeTask. Missing offer details → `parentOfferHash = null` + healing log.

---

## 6. State Machine — Multi-Region Stepper (Layer 3)

### 6.1 StateMachine

```kotlin
@Singleton
class StateMachine @Inject constructor(
    private val flowStepper: FlowRegionStepper,
    private val platformSteppers: Map<Platform, @JvmSuppressWildcards PlatformRegionStepper>,
    private val crossPlatformStepper: CrossPlatformRegionStepper,
    private val healingPolicy: HealingPolicy,
    private val effectMap: EffectMap,
) {
    fun step(prev: AppState, obs: Observation): Transition {
        // R0 — observation-driven
        val nextFlow = flowStepper.step(prev.regions.flow, obs)

        // R2+ — per-platform; only the matching platform steps
        val nextPlatforms = stepPlatforms(
            prev.regions.platforms, prev.regions.flow, nextFlow, obs, healingPolicy,
        )

        // R1 — derived from post-step platform snapshots
        val nextCrossPlatform = crossPlatformStepper.step(
            prev.regions.crossPlatform, prev.regions.platforms, nextPlatforms, obs,
        )

        val next = prev.copy(
            regions = Regions(nextFlow, nextCrossPlatform, nextPlatforms),
            timestamp = obs.timestamp,
            correlationVersion = prev.correlationVersion + 1,
        )
        return Transition(next, effectMap.diff(prev, next, obs))
    }

    private fun stepPlatforms(
        prev: Map<Platform, PlatformRegion>,
        prevFlow: FlowRegion, nextFlow: FlowRegion,
        obs: Observation, healing: HealingPolicy,
    ): Map<Platform, PlatformRegion> {
        val platform = obs.platform ?: return prev
        val stepper = platformSteppers[platform]
            ?: platformSteppers[Platform.Unknown]!!   // DefaultPlatformRegionStepper
        val prevRegion = prev[platform] ?: PlatformRegion(platform)
        val nextRegion = stepper.step(prevRegion, prevFlow, nextFlow, obs, healing)
        return prev + (platform to nextRegion)
    }
}
```

### 6.2 FlowRegionStepper

Takes the IR, updates Flow region. Manages offer lifecycle: set `pendingOffer`
on `OfferPresented`, run evaluator, clear on accept/decline click.

### 6.3 PlatformRegionStepper (with healing)

This is the new home of mode inference + healing (replaces the old per-state
reducers and state factories). Each platform heals independently.

```kotlin
@Singleton
class PlatformRegionStepper @Inject constructor(
    private val sessionTracker: SessionTracker,
    private val jobTracker: JobTracker,
    private val taskTracker: TaskTracker,
) {
    fun step(
        prev: PlatformRegion,
        prevFlow: FlowRegion, nextFlow: FlowRegion,
        obs: Observation, healing: HealingPolicy,
    ): PlatformRegion {
        val implied = inferModeFrom(nextFlow, obs)
        return when (implied.verdict) {
            Verdict.NoChange       -> prev.copy(confidence = ModeConfidence.empty())
            Verdict.PlausibleApply -> applyMode(prev, implied, obs)
            Verdict.Implausible    -> healOrAccrue(prev, implied, obs, healing)
        }
    }
}
```

A generic `DefaultPlatformRegionStepper` handles most platforms. Platform-specific
overrides are bound via `Map<Platform, PlatformRegionStepper>` in Hilt only when
a platform has unique inference rules.

### 6.4 EffectMap

Replaces 9 reducers + 8 factories. Diffs each region; emits effects.

```kotlin
class EffectMap {
    fun diff(prev: AppState, next: AppState, obs: Observation): List<AppEffect> =
        buildList {
            addAll(diffFlowRegion(prev.regions.flow, next.regions.flow, obs))
            (prev.regions.platforms.keys + next.regions.platforms.keys)
                .distinct().forEach { p ->
                    addAll(diffPlatformRegion(
                        p, prev.regions.platforms[p], next.regions.platforms[p], obs
                    ))
                }
            addAll(diffCrossPlatformRegion(
                prev.regions.crossPlatform, next.regions.crossPlatform, obs
            ))
        }
}
```

Flow-region transitions → UI overlay effects (show/hide offer overlay).
Platform-region transitions → durable effects (start session, start odometer,
persist task). Cross-platform transitions → aggregate bookkeeping.

Effects gain an **idempotency key** for crash recovery:

```kotlin
sealed class AppEffect {
    abstract val effectKey: String?
    data class StartOdometer(...) : AppEffect()
    data class StartSession(val sessionId: String) : AppEffect() {
        override val effectKey = "session.start:$sessionId"
    }
    data class ShowOfferOverlay(val offerHash: String) : AppEffect() {
        override val effectKey = null    // transient, never replayed
    }
}
```

---

## 7. Event Sourcing & Crash Recovery (Layer 3)

Two persistence streams replace `StateRecoveryRepository`.

### 7.1 Observation log

Append-only Room table:

```kotlin
@Entity(tableName = "observations",
    indices = [Index("sessionId"), Index("occurredAt"), Index("correlationVersion")])
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val sequenceId: Long = 0,
    val occurredAt: Long,
    val sessionId: String?,
    val pipelineId: String,
    val ruleId: String?,
    val platform: String?,
    val flow: String?,
    val modeHint: String?,
    val parsedJson: String,
    val captureId: String?,
    val metadataJson: String,
    val correlationVersion: Long,
)
```

### 7.2 AppState snapshots

Periodic snapshot covering all regions, one row every N=5 observations and at
major transitions (session start/end, job start/end, healing event). Old
snapshots pruned after 24h.

```kotlin
@Entity(tableName = "app_state_snapshots")
data class AppStateSnapshotEntity(
    @PrimaryKey val correlationVersion: Long,
    val capturedAt: Long,
    val sessionId: String?,
    val stateJson: String,          // full AppState (all regions) JSON
)
```

### 7.3 Recovery on startup

```kotlin
suspend fun restore(): AppState {
    val snap = snapshotDao.latest() ?: return AppState()
    val tail = obsDao.since(snap.correlationVersion)
    return tail.fold(snap.state) { acc, obs ->
        stateMachine.step(acc, obs.toObservation()).newState
    }
}
```

### 7.4 Side-effect idempotency

`SideEffectEngine.process(effect, recovering: Boolean)` gains a recovery flag:

- Effects with `effectKey` are checked against `effects_fired` Room table;
  already-fired effects skip.
- External effects (`UpdateBubble`, `ShowOfferOverlay`, `PlayNotificationSound`,
  `ClickNode`) are unconditionally suppressed when `recovering = true`.
- Loopback effects (timer fires, evaluation results) are replayed
  deterministically.

`StateRecoveryRepository` is deleted.

---

## 8. Versioning & Loader (ADR-0003)

### 8.1 Module placement

```
:domain              Pipeline, Observation, Flow/Mode, ParsedFields, AppState, Regions,
                     FlowRegion, PlatformRegion, CrossPlatformRegion, Job, Task, Session,
                     CaptureSchema, CaptureEnvelope, ReplayMetadata
:core:database       ObservationDao, AppStateSnapshotDao, EffectsFiredDao
:core:data           CaptureService, CaptureBus, CaptureFileStore, RulesetCatalog
:app                 Pipelines, RuleEngine, StateMachine + steppers, EffectMap, Hilt, UI
```

### 8.2 Version constants

```kotlin
object PipelineRegistry {
    val pipelines = mapOf(
        "accessibility"            to 1,
        "accessibility.window"     to 1,
        "accessibility.click"      to 1,
        "accessibility.long_click" to 1,
        "accessibility.scroll"     to 1,
        "accessibility.focus"      to 1,
        "notification"             to 1,
    )
}

object RuleEngine {
    const val VERSION = 1
    const val MAX_SUPPORTED_FORMAT_VERSION = 2
}

object StateMachineContract {
    const val API_VERSION_MAJOR = 1
    const val API_VERSION_MINOR = 0
    val SUPPORTED_FLOWS: Set<String> = Flow.entries.map { it.wire }.toSet()
    val SUPPORTED_MODES: Set<String> = Mode.entries.map { it.name.lowercase() }.toSet()
}
```

### 8.3 RulesetLoader

Implements ADR-0003's seven-step compatibility check. Lives next to
`JsonRuleInterpreter`. Both `loadDefaults()` and the future CDN fetch path
go through it. If a rule's `flow:` output is not in `SUPPORTED_FLOWS`, the
loader rejects the entire bundle and falls back to the previous good version.

---

## 9. Ruleset Distribution

### 9.1 New repository: `dashbuddy-matchers`

Apache-2.0 license (per Pillar 2 architectural posture).

```
matchers/
├── doordash/
│   ├── screen/*.json5
│   ├── click/*.json5
│   └── notification/*.json5
├── uber/
├── instacart/
└── _shared/
tests/
├── doordash/
│   └── screen/<name>/snapshots/*.json
schema/
└── ruleset.schema.json5
dist/
└── (CI output — not committed)
tools/
├── compile/          # JSON5 → JSON bundling
├── sign/             # Ed25519 signing
└── intake/           # capture-upload PR bot
```

### 9.2 CI/CD pipeline

1. Lint every rule file against `schema/ruleset.schema.json5`.
2. Run replay tests: load captured envelopes, run through matchers, assert
   classification and parsed-field equivalence.
3. On tag push: compile JSON5 → minified JSON, bundle, sign with Ed25519,
   publish to GitHub Releases.

### 9.3 App-side fetch (Phase 7)

`RulesetFetcher` in `:core:network`: HTTPS GET of latest release manifest.
Verify Ed25519 signature against pinned public key. Write to app private dir.
Failure falls back to bundled defaults silently.

### 9.4 Capture upload (Phase 7+)

Opt-in. PII scrub at edge. Upload `_unknown` captures by default for upstream
contribution. GitHub Actions intake bot opens PRs with contributed envelopes.

---

## 10. Bubble HUD

### 10.1 Tab structure

Region 0 takes UI control transiently for offers (only). Region 1 owns the
default aggregate tab. Region 2+ each get their own tab. Tabs are derived from
`regions.platforms.keys` — adding platform matchers automatically gets a tab.

```kotlin
@Composable
fun BubbleHud(state: AppState) {
    if (state.regions.flow.pendingOffer != null) {
        OfferOverlay(state.regions.flow.pendingOffer)
        return
    }
    val tabs = listOf(Tab.Aggregate) +
        state.regions.platforms.keys.map(Tab::Platform)
    val selected by rememberSaveable { mutableStateOf(Tab.Aggregate) }
    TabRow(tabs, selected)
    when (val tab = selected) {
        Tab.Aggregate   -> CrossPlatformView(
            state.regions.crossPlatform, state.regions.platforms)
        is Tab.Platform -> PlatformView(
            platform = state.regions.platforms[tab.platform]!!,
            context = state.regions.crossPlatform,
        )
    }
}
```

### 10.2 Per-flow HUD content

All timers use the 1Hz `rememberNow()` composable ticker.

| Flow                    | Badge      | Primary                       | Secondary                  | Live Elements                      |
|-------------------------|------------|-------------------------------|----------------------------|------------------------------------|
| (offline)               | OFFLINE    | Last session or "Not working" | Ratings                    | —                                  |
| idle                    | WAITING    | "Waiting for orders"          | Wait estimate              | Session $, mi, spot-save countdown |
| offer:presented         | OFFER      | Store(s), $Pay, Score         | $/mi, $/hr, recommendation | Countdown, session $, mi           |
| task:pickup:navigation  | PICKUP     | Store name                    | Deadline                   | Session $, mi                      |
| task:pickup:arrived     | AT STORE   | Store name, activity          | Deadline, items, Red Card  | Wait timer, session $, mi          |
| task:dropoff:navigation | DELIVERING | "To customer"                 | Deadline                   | Session $, mi                      |
| task:dropoff:arrived    | AT DOOR    | —                             | —                          | Wait timer, session $, mi          |
| post:task               | COMPLETED  | +$X.XX                        | Tips breakdown             | Session $, mi                      |
| (paused)                | PAUSED     | "Session Paused"              | —                          | Remaining countdown                |
| (post-session)          | DONE       | Total earnings                | Duration, rate             | —                                  |

---

## 11. Migration Phases

Each phase is independently shippable. No translation/dual-run.

### Phase 0 — Notification bug fix (standalone, ship first)

- Fix `NotificationMapper.kt`: `Bundle.getString()` →
  `extras.getCharSequence(key)?.toString()` for title, text, bigText.
- `NotificationFilter.kt`: add `&& raw.isClearable` to drop foreground-service
  spam.

### Phase 1 — Foundations

Pure additions; nothing breaks.

- Create `Flow`, `Mode`, `Platform`, `TaskPhase`, `TaskSubFlow`, `SessionType`
  enums in `:domain`.
- Create `Observation`, `FlowObservation`, `ParsedFields` in `:domain`.
- Create `Pipeline`, `CapturablePipeline`, `CompositePipeline` in `:domain`.
- Create `CaptureSchema`, `CaptureEnvelope`, `ReplayMetadata` in `:domain`.
- Create `Regions`, `FlowRegion`, `PlatformRegion`, `CrossPlatformRegion`,
  `ModeConfidence` in `:domain`.
- Create `Session`, `Job`, `Task`, `PendingOffer` in `:domain`.
- Create `PipelineRegistry`, `RuleEngine`, `StateMachineContract` constants.
- Add `state:` block to `RuleCompiler`; **drop `target:` parsing**.
- Create `RulesetLoader` with seven-step check; route
  `JsonRuleInterpreter.loadDefaults()` through it.
- Update bundled `rules.default.json` header with `pipelines`,
  `state_machine`, and per-rule `state:` blocks; **remove all `target:` keys**.
- Delete `Screen`, `ClickInfo`, `NotificationInfo` Kotlin enums/sealed classes.

### Phase 2 — Pipeline interface + capture (disk-only)

- Create `CaptureBus`, `CaptureService`, `CaptureFileStore`, `RulesetCatalog`
  in `:core:data`.
- Implement disk-only writer with platform-aware paths and fully-qualified
  filenames.
- Refactor `AccessibilityPipeline` into `CompositePipeline` with
  `WindowSubPipe`, `ClickSubPipe`, `LongClickSubPipe`, `ScrollSubPipe`,
  `FocusSubPipe` (latter three scaffolds; only Window+Click wired initially).
- Eliminate `ScreenFactory`: classification stays in `ScreenClassifier`;
  snapshotting moves to `CaptureService`; odometer enrichment moves to
  state-machine-side.
- Eliminate `ClickFactory` and `NotificationFactory`.
- Create `SensitiveGate` extracted from `SensitiveScreenMatcher`.
- Refactor `PipelineV2` to consume Hilt `Set<Pipeline<*, Observation>>`.
- Delete `SnapshotRepository`, `ClickLogRepository`, `Breadcrumbs`.

### Phase 3 — Observation IR over the wire

Pipelines emit `FlowObservation` directly.

- Update `ScreenClassifier`, `ClickClassifier`, `NotificationClassifier` to
  return `FlowObservation` shapes (carrying `flow`, `modeHint`, `parsed`,
  `ruleId`).
- Delete `ScreenInfo` sealed hierarchy.
- Delete `ScreenUpdateEvent`, `ClickEvent`, `NotificationEvent` — replaced by
  `Observation.Screen/Click/Notification`.
- Existing reducers temporarily stub out (Phase 4 deletes them);
  `StateManagerV2` routes `FlowObservation` into the new state machine.

### Phase 4 — AppState multi-region + StateMachine

The big-bang state refactor.

- Delete `AppStateV2`, `Reducer.kt`, all 9 per-state reducers, all 8 factories.
- Create `AppState`, `Regions`, all region types in `:domain`.
- Create `StateMachine`, `FlowRegionStepper`, `PlatformRegionStepper`,
  `CrossPlatformRegionStepper`, `HealingPolicy`, `EffectMap`,
  `SessionTracker`, `JobTracker`, `TaskTracker`, `OfferTracker`.
- Refactor each effect handler to dispatch on per-region transition tuples.
- Vocabulary rename pass: `dash → session` everywhere.
- Bubble HUD refactored to read `AppState` — primarily Region 2 (platform),
  with Region 0 (flow) controlling the UI when `pendingOffer != null`.

### Phase 5 — Event sourcing & crash recovery

- Create `ObservationEntity`/`ObservationDao`,
  `AppStateSnapshotEntity`/`Dao`, `EffectsFiredEntity`/`Dao` in
  `:core:database` (DB version bump).
- StateMachine writes observation log on every accepted observation; writes
  snapshot every 5 observations and at major transitions.
- Startup path: load latest snapshot + tail-replay newer observations.
- `SideEffectEngine` gains `recovering: Boolean`; effects with `effectKey`
  deduped; external effects suppressed during recovery.
- Delete `StateRecoveryRepository`.

### Phase 6 — Parser DSL completion (per-parser equivalence-tested)

For **each** Kotlin parser:

1. Author equivalent `parse:` DSL block in the rule.
2. Add per-parser equivalence test: feed same captured `UiNode` corpus through
   old Kotlin parser and new DSL ruleset; assert `ParsedFields` equivalence.
3. **Only delete the Kotlin parser when the equivalence test passes** for
   the full corpus for that screen type.

### Phase 7 — Multi-platform readiness + ruleset distribution

- Create `dashbuddy-matchers` repo and CI/CD per Section 9.
- App-side `RulesetFetcher` + signature verification.
- App-side capture upload worker + opt-in toggle.
- `EnabledPlatforms`, watched-package settings (#217).
- Cache + rollback affordance.

---

## 12. Files to Create / Modify / Delete

### Create

**`:domain`**

- `domain/.../pipeline/Pipeline.kt` — `Pipeline`, `CapturablePipeline`, `CompositePipeline`
- `domain/.../pipeline/Observation.kt` — `Observation`, `FlowObservation`
- `domain/.../state/Flow.kt` — `Flow` enum
- `domain/.../state/Mode.kt` — `Mode` enum
- `domain/.../state/Platform.kt` — `Platform` enum
- `domain/.../state/TaskPhase.kt` — `TaskPhase` enum
- `domain/.../state/TaskSubFlow.kt` — `TaskSubFlow` enum
- `domain/.../state/ParsedFields.kt` — sealed class + all subtypes
- `domain/.../state/AppState.kt` — `AppState`, `Regions`
- `domain/.../state/FlowRegion.kt` — `FlowRegion`, `PendingOffer`
- `domain/.../state/PlatformRegion.kt` — `PlatformRegion`, `ModeConfidence`
- `domain/.../state/CrossPlatformRegion.kt` — `CrossPlatformRegion`, `PeriodTotals`
- `domain/.../state/Session.kt` — `Session`
- `domain/.../state/Job.kt` — `Job`
- `domain/.../state/Task.kt` — `Task`
- `domain/.../capture/CaptureSchema.kt`
- `domain/.../capture/CaptureEnvelope.kt`
- `domain/.../capture/ReplayMetadata.kt`

**`:app`**

- `app/.../state/StateMachine.kt`
- `app/.../state/FlowRegionStepper.kt`
- `app/.../state/PlatformRegionStepper.kt`
- `app/.../state/CrossPlatformRegionStepper.kt`
- `app/.../state/HealingPolicy.kt`
- `app/.../state/SessionTracker.kt`
- `app/.../state/JobTracker.kt`
- `app/.../state/TaskTracker.kt`
- `app/.../state/OfferTracker.kt`
- `app/.../state/EffectMap.kt`
- `app/.../rules/RulesetLoader.kt`
- `app/.../rules/RuleEngineConstants.kt`
- `app/.../pipeline/PipelineRegistry.kt`
- `app/.../pipeline/accessibility/WindowSubPipe.kt`
- `app/.../pipeline/accessibility/ClickSubPipe.kt`
- `app/.../pipeline/accessibility/LongClickSubPipe.kt` (scaffold)
- `app/.../pipeline/accessibility/ScrollSubPipe.kt` (scaffold)
- `app/.../pipeline/accessibility/FocusSubPipe.kt` (scaffold)
- `app/.../pipeline/accessibility/SensitiveGate.kt`

**`:core:data`**

- `core/data/.../capture/CaptureBus.kt`
- `core/data/.../capture/CaptureService.kt`
- `core/data/.../capture/CaptureFileStore.kt`
- `core/data/.../capture/RulesetCatalog.kt`

**`:core:database`**

- `core/database/.../observation/ObservationEntity.kt`
- `core/database/.../observation/ObservationDao.kt`
- `core/database/.../snapshot/AppStateSnapshotEntity.kt`
- `core/database/.../snapshot/AppStateSnapshotDao.kt`
- `core/database/.../effects/EffectsFiredEntity.kt`
- `core/database/.../effects/EffectsFiredDao.kt`

### Modify

- `app/.../pipeline/PipelineV2.kt` — Hilt multibind consumer
- `app/.../state/StateManagerV2.kt` — use `StateMachine` + multi-region recovery
- `app/.../state/AppEffect.kt` — add `effectKey`, rename dash→session effects
- `app/.../state/effects/SideEffectEngine.kt` — `recovering` flag, idempotency
- `app/.../rules/RuleCompiler.kt` — `state:` block, **drop `target:`**
- `app/.../rules/JsonRuleInterpreter.kt` — call `RulesetLoader` first
- `app/src/main/assets/rules.default.json` — header, `state:` blocks, **remove
  `target:` everywhere**, vocabulary rename
- `app/.../pipeline/notification/mapper/NotificationMapper.kt` — Phase 0 fix
- `app/.../pipeline/notification/NotificationFilter.kt` — Phase 0 fix
- `app/.../ui/bubble/BubbleScreen.kt` — read multi-region `AppState`; tabs
- `core/database/.../DashBuddyDatabase.kt` — DB version bump
- `domain/.../model/dash/*` — rename package to `model/session/`; `DashType` →
  `SessionType`

### Delete

- `app/.../state/AppStateV2.kt`
- `app/.../state/Reducer.kt`
- `app/.../state/reducers/*.kt` (9 files)
- `app/.../state/factories/*.kt` (8 files)
- `app/.../pipeline/accessibility/event/type/window/ScreenFactory.kt`
- `app/.../pipeline/accessibility/event/type/view/clicked/ClickFactory.kt`
- `app/.../pipeline/notification/NotificationFactory.kt`
- `domain/.../model/accessibility/ScreenInfo.kt`
- `domain/.../model/accessibility/Screen.kt`
- `domain/.../model/accessibility/ClickInfo.kt`
- `domain/.../model/notification/NotificationInfo.kt`
- `domain/.../model/state/ScreenUpdateEvent.kt`
- `domain/.../model/state/ClickEvent.kt`
- `domain/.../model/state/NotificationEvent.kt`
- `core/data/.../log/SnapshotRepository.kt`
- `core/data/.../log/ClickLogRepository.kt`
- `core/data/.../state/StateRecoveryRepository.kt`
- All Kotlin parsers under `app/.../pipeline/.../parsers/` (Phase 6, only after
  per-parser equivalence test passes)

### Reused (no change)

- `domain/.../model/offer/ParsedOffer.kt` — kept; wrapped by `OfferFields`
- `domain/.../evaluation/OfferEvaluator.kt` — invoked from `FlowRegionStepper`
- `RuleCompiler` predicate/transform compilation — extended, not rewritten
- `UiNode` — kept; helpers added per ADR-0001

---

## 13. Modularization Guarantees

### Per-axis isolation

| Working on...                         | Touches                                                                    | Does NOT touch                     |
|---------------------------------------|----------------------------------------------------------------------------|------------------------------------|
| Adding a new rule (existing platform) | `rules.default.json` only                                                  | No Kotlin changes                  |
| Adding a new platform                 | `Platform` enum; ship rules; bind `PlatformRegionStepper` (or use default) | DoorDash state, Region 0, Region 1 |
| Tuning healing thresholds             | `HealingPolicy` constants                                                  | Steppers themselves                |
| Bubble HUD redesign                   | `ui/bubble/` only                                                          | `AppState` shape (stable contract) |
| New pipeline (e.g., clipboard)        | New source + sub-pipe + Hilt bind                                          | Other pipelines; PipelineV2        |
| New side effect                       | `AppEffect` subtype + handler + `EffectMap.diff` branch                    | Regions, pipelines                 |
| Recovery logic                        | `StateMachine.restore()` + `EffectsFiredDao`                               | Steppers (same step function)      |
| Cross-platform aggregation            | `CrossPlatformRegionStepper`                                               | Per-platform regions (read-only)   |
| Capture upload (Phase 7)              | `core/data/.../capture/upload/` (new)                                      | Capture write path                 |

### Module boundary rules

1. **`:domain` is platform-agnostic.** No string literal mentioning "doordash",
   "uber", "instacart". The `Platform` enum is the only place platforms exist
   as types. CI lint rule enforced.
2. **Region steppers don't reference each other.** `FlowRegionStepper` doesn't
   depend on `PlatformRegionStepper` or vice versa. `StateMachine` wires them;
   steppers are independently testable.
3. **Effects are diffed per-region.** `EffectMap.diff` calls per-region diff
   functions independently. Adding a new effect for one region doesn't risk
   regressing others.

---

## 14. Verification

### Phase 0

Notification capture has non-empty text; foreground-service spam gone.

### Phase 1

`./gradlew :app:build` clean. Unit tests for `Flow.fromWire()`, `RulesetLoader`
rejection paths, vocabulary validator. Bundled `rules.default.json` loads with
new header and no `target:` keys.

### Phase 2

`./gradlew testDebugUnitTest` green. Pipeline contract test (each `Pipeline`
registers, produces `Observation`). `CaptureService` round-trip test: write →
read → identical payload. On-device: capture files populated with fully
qualified filenames; per-classification quotas honored.

### Phase 3

`./gradlew testDebugUnitTest --tests "*AllMatchersSuite*"` passes. Captured
envelopes produce `FlowObservation` with expected `flow` and `ruleId`.

### Phase 4

Full snapshot regression suite green. `MultiRegionStepperTest` — hand-authored
observation sequences, assert each region's resulting state.
`HealingTest` — "observed pickup without offer" → mode region heals.
`BackGestureFlickerTest` — pickup → idle → pickup → mode stays stable.

### Phase 5

`RecoveryTest` — feed 100 observations, snapshot, terminate, restore, feed 50
more, assert final `AppState` equals feed-all-at-once. Idempotency: no
double-fires; external effects suppressed during recovery.

### Phase 6

**Per-parser equivalence test** — for each Kotlin parser, old vs. new on
captured corpus, assert `ParsedFields` equivalence. Parser only deleted when
test passes.

### Phase 7

Integration test of `RulesetFetcher` against fixture release;
signature-verification rejection test; cache + rollback test.

### All phases

On-device: launch app, run a test session, verify bubble HUD renders correctly.
UI correctness requires on-device validation.

---

## 15. Open / Deferred Decisions

- **`effectKey` cardinality.** Refine catalog in Phase 4 as `EffectMap` is built.
- **Healing thresholds.** Default 2 observations / 10s. Tunable via
  `HealingPolicy`. Revisit after on-device testing.
- **Snapshot cadence.** Default N=5. Tunable; revisit if recovery stalls or
  Room write rate is high.
- **Per-region transition tables.** Deferred. Add only if analytics or
  partial-recovery use cases justify.
- **Generic `DefaultPlatformRegionStepper` vs. per-platform overrides.** Build
  default first; add overrides only when a real platform forces it.
- **Cross-platform aggregation cadence.** Recomputes on every observation.
  Cheap today; introduce debounce only if profiling shows hot spots.
- **Snapshot DTO shape stability.** If `AppState` shape changes post-Phase 5,
  snapshot blob migration logic needed; defer until first real schema break.
- **Sub-pipes that don't yet exist.** `LongClickSubPipe`, `ScrollSubPipe`,
  `FocusSubPipe` created as scaffolds in Phase 2; wired only when a source
  and ruleset section exist for them.
- **Capture upload PII surface.** PII scrub rules are part of Phase 7 design.

---

## Cross-references

- **ADR-0001** — matcher rule format; predicate vocabulary and parse sub-language
- **ADR-0002** — cross-platform state taxonomy; flow/mode vocabulary;
  mode-is-inferred amendment
- **ADR-0003** — four-layer versioning; loader compatibility checks; replay
  metadata
- **ADR-0004** — canonical pipeline architecture (superseded by Sections 2-3
  of this ADR)
- **#192** — matchers infrastructure epic
- **#193** — aggregation RFC
- **#211** — JSON rule interpreter audit (output schema contract)
- **#214** — contributor onboarding tooling
- **#217** — multi-platform watched-package configuration