package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.identity
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.core.pipeline.CaptureWriter
import cloud.trotter.dashbuddy.core.pipeline.FrameGate
import cloud.trotter.dashbuddy.core.pipeline.passesContentGates
import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed.WindowsChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toUiNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET

/**
 * Unified accessibility pipeline: merges screen and click event sources,
 * classifies through the rule engine, captures unique observations for replay,
 * and forwards only known, non-sensitive observations to the state machine.
 *
 * Flow: raw events → PipelineEvent → classify → gate sensitive → capture
 * (identity-based dedup) → gate unknown → state machine.
 */
@Singleton
class AccessibilityPipeline @Inject constructor(
    private val contentChangedPipeline: ContentChangedPipeline,
    private val stateChangedPipeline: StateChangedPipeline,
    private val windowsChangedPipeline: WindowsChangedPipeline,
    private val source: AccessibilitySource,
    private val classifier: ObservationClassifier,
    private val captureWriter: CaptureWriter,
    private val platformPreferences: PlatformPreferences,
    private val stats: PipelineStats,
) {
    companion object {
        const val SCREEN_PIPELINE_ID = "accessibility.window"
        const val CLICK_PIPELINE_ID = "accessibility.click"
    }

    /** Identity dedup + content-bearing UNKNOWN suppression (#360). */
    private val frameGate = FrameGate()

    // ── Source flows ────────────────────────────────────────────────────

    private fun screenEvents(): Flow<PipelineEvent.Screen> = merge(
        contentChangedPipeline.output(),
        stateChangedPipeline.output(),
        windowsChangedPipeline.output(),
    ).map { snapshot ->
        PipelineEvent.Screen(
            timestamp = System.currentTimeMillis(),
            tree = snapshot.tree,
            snapshot = snapshot,
            packageName = snapshot.packageName,
        )
    }

    private fun clickEvents(): Flow<PipelineEvent.Click> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED }
        .mapNotNull { event ->
            try {
                val sourceNode = event.source ?: return@mapNotNull null
                val node = sourceNode.toUiNode() ?: return@mapNotNull null
                PipelineEvent.Click(
                    timestamp = System.currentTimeMillis(),
                    node = node,
                    packageName = event.packageName?.toString(),
                )
            } catch (e: Exception) {
                // A malformed node tree must cost one click, not the whole
                // sensing upstream (#430) — this was the only unwrapped
                // mapping call in the chain.
                stats.onMappingFailure()
                Timber.w(e, "Click mapping failed — dropping click event")
                null
            }
        }

    // ── Main pipeline ──────────────────────────────────────────────────

    fun output(): Flow<Observation> = merge(screenEvents(), clickEvents())
        // Gate: drop everything until rulesets load (#432). The sensitive gate
        // below is RULE-driven — a frame classified before rules exist would
        // bypass it straight into the UNKNOWN capture path.
        .filter { event ->
            val ready = classifier.isReady
            if (!ready) {
                stats.onDroppedAwaitingRules()
                Timber.w("Dropping %s — rulesets not loaded yet", event::class.simpleName)
            }
            ready
        }
        // Classify through the unified rule engine (typed: FlowObservation, #361)
        .map { event -> classifier.classify(event) to event }

        // Gate: drop sensitive/noise observations (pledge: never store or
        // forward) — the shared content gate (#399).
        .filter { (obs, _) ->
            val passes = passesContentGates(obs)
            if (!passes) stats.onContentGateDrop(obs.parsed)
            passes
        }

        // Gate: drop observations from disabled platforms (defense-in-depth)
        .filter { (_, event) ->
            val pkg = when (event) {
                is PipelineEvent.Screen -> event.packageName
                is PipelineEvent.Click -> event.packageName
                else -> null
            }
            val platform = Platform.fromPackage(pkg)
            val allowed = platform == Platform.Unknown ||
                platform in platformPreferences.enabledPlatforms.value
            if (!allowed) stats.onDisabledPlatformDrop()
            allowed
        }

        // Dedup + Capture: write unique observations to disk, skip duplicates.
        // Known screens dedup by identity; UNKNOWN frames dedup by tree/node
        // content hash in a rolling seen-set (#360) — lastIdentity semantics
        // (an UNKNOWN interlude doesn't reset known-screen dedup) are preserved
        // inside FrameGate.
        .mapNotNull { (obs, event) ->
            val contentHash = when (event) {
                is PipelineEvent.Screen -> event.tree.stableHash
                is PipelineEvent.Click ->
                    clickDedupHash(event.node, classifier.lastScreenTarget)
                else -> null
            }
            if (!frameGate.admit(obs, contentHash)) {
                stats.onDuplicateSuppressed()
                Timber.v("Dedup: suppressed %s", obs.target)
                return@mapNotNull null
            }
            // Capture via the shared writer; smart-casts replace the old
            // unchecked downcasts (#361).
            when {
                obs is Observation.Screen && event is PipelineEvent.Screen ->
                    captureWriter.captureScreen(obs, event)
                obs is Observation.Click && event is PipelineEvent.Click ->
                    captureWriter.captureClick(obs, event, classifier.lastScreenTarget)
                else -> obs
            }
        }

        // Gate: don't forward UNKNOWN observations to state machine
        .filter { obs ->
            val isUnknown = obs.target == UNKNOWN_TARGET
            if (isUnknown) {
                stats.onUnknownDropped()
                Timber.v("Unknown gate: captured but not forwarding %s", obs.target)
            }
            !isUnknown
        }
        .onEach { stats.onForwarded() }

}

/**
 * Dedup hash for a click capture that includes the current screen target.
 *
 * Same-shape buttons (e.g. DoorDash's `primary_action_button`) appear on many
 * screens with different labels and meanings. [UiNode.structuralHash] ignores
 * text, so without the screen mix all such clicks collide in the per-bucket
 * dedup set inside the disk capture bus (`DiskCaptureBus` in `:core:data`)
 * and only the first one in a session survives — silently dropping later
 * clicks the developer needs to triage screen-specific behavior.
 */
internal fun clickDedupHash(node: UiNode, screenTarget: String?): Int =
    31 * node.structuralHash + (screenTarget?.hashCode() ?: 0)
