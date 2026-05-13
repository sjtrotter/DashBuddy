package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.pipeline.rules.ParsedFieldsFactory
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single classification entry point for all pipeline events.
 *
 * Dispatches to the appropriate compiled ruleset (screen, click, notification)
 * based on event type. Caches the last classified screen target so that click
 * observations are enriched with screen context.
 *
 * Replaces the former `ScreenClassifier`, `ClickClassifier`, and
 * `NotificationClassifier` classes.
 */
@Singleton
class ObservationClassifier @Inject constructor(
    private val interpreter: JsonRuleInterpreter,
    private val metadataProvider: ReplayMetadataProvider,
) {
    /** Last classified screen target — updated after every non-sensitive Screen classification. */
    @Volatile
    var lastScreenTarget: String? = null
        private set

    @Volatile
    private var lastScreenTimestamp: Long = 0L

    fun classify(event: PipelineEvent): Observation {
        val platformWire = when (event) {
            is PipelineEvent.Screen -> Platform.fromPackage(event.packageName).wire
            is PipelineEvent.Click -> Platform.fromPackage(event.packageName).wire
            is PipelineEvent.Notification -> Platform.fromPackage(event.raw.packageName).wire
        }.takeIf { it != Platform.Unknown.wire }

        return when (event) {
            is PipelineEvent.Screen -> classifyScreen(event, platformWire)
            is PipelineEvent.Click -> classifyClick(event, platformWire)
            is PipelineEvent.Notification -> classifyNotification(event, platformWire)
        }
    }

    // ── Screen ──────────────────────────────────────────────────────────

    private fun classifyScreen(event: PipelineEvent.Screen, platformWire: String?): Observation.Screen {
        val ruleset = interpreter.screenRuleset
        if (ruleset == null) {
            Timber.w("ObservationClassifier: no screen ruleset loaded")
            return makeScreenObservation("UNKNOWN", null, null, ParsedFields.None, null)
        }

        val result = ruleset.matchFirst(event.tree, platformWire)
        if (result == null) {
            Timber.i("SCREEN: UNKNOWN")
            return makeScreenObservation("UNKNOWN", null, null, ParsedFields.None, null)
        }

        Timber.i("SCREEN: ${result.intent}")
        if (result.effects.isNotEmpty()) {
            Timber.d("SCREEN: ${result.intent} has ${result.effects.size} effect(s)")
        }

        val parsed = ParsedFieldsFactory.create(result.shape, result.fields)
        val obs = makeScreenObservation(
            screenName = result.intent,
            flow = result.flow,
            modeHint = result.modeHint,
            parsed = parsed,
            ruleId = result.ruleId,
            effects = result.effects,
            transitionOverrides = result.transitionOverrides,
            expectedOutcomes = result.outcomes,
        )

        // Cache last non-sensitive screen for click enrichment
        if (obs.parsed !is ParsedFields.SensitiveFields) {
            lastScreenTarget = obs.target
            lastScreenTimestamp = obs.timestamp
        }

        return obs
    }

    private fun makeScreenObservation(
        screenName: String,
        flow: Flow?,
        modeHint: Mode?,
        parsed: ParsedFields,
        ruleId: String?,
        effects: List<RequestedEffect> = emptyList(),
        transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>> = emptyMap(),
        expectedOutcomes: Set<Flow>? = null,
    ) = Observation.Screen(
        timestamp = System.currentTimeMillis(),
        captureId = null,
        ruleId = ruleId,
        metadata = metadataProvider.current(),
        flow = flow,
        modeHint = modeHint,
        parsed = parsed,
        target = screenName,
        effects = effects,
        transitionOverrides = transitionOverrides,
        expectedOutcomes = expectedOutcomes,
    )

    // ── Click ───────────────────────────────────────────────────────────

    private fun classifyClick(event: PipelineEvent.Click, platformWire: String?): Observation.Click {
        val ruleset = interpreter.clickRuleset
        if (ruleset != null) {
            val result = ruleset.matchFirst(event.node, platformWire, lastScreenTarget)
            if (result != null) {
                return Observation.Click(
                    timestamp = System.currentTimeMillis(),
                    captureId = null,
                    ruleId = result.ruleId,
                    metadata = metadataProvider.current(),
                    flow = result.flow,
                    modeHint = result.modeHint,
                    parsed = ParsedFields.ClickFields(intent = result.intent),
                    target = result.intent,
                    effects = result.effects,
                    transitionOverrides = result.transitionOverrides,
                    expectedOutcomes = result.outcomes,
                    screenTarget = lastScreenTarget,
                )
            }
        }

        // Unknown click — preserve node details for future classification
        val nodeId = event.node.viewIdResourceName
            ?.takeIf { it.isNotBlank() && it != "no_id" }
        val nodeText = event.node.text?.takeIf { it.isNotBlank() }
        Timber.d("ObservationClassifier: UNKNOWN click — id=$nodeId text=$nodeText")
        return Observation.Click(
            timestamp = System.currentTimeMillis(),
            captureId = null,
            ruleId = null,
            metadata = metadataProvider.current(),
            flow = null,
            modeHint = null,
            parsed = ParsedFields.ClickFields(
                intent = "unknown",
                nodeId = nodeId,
                nodeText = nodeText,
            ),
            target = "UNKNOWN",
            screenTarget = lastScreenTarget,
        )
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun classifyNotification(event: PipelineEvent.Notification, platformWire: String?): Observation.Notification {
        val ruleset = interpreter.notificationRuleset
        if (ruleset != null) {
            val result = ruleset.matchFirst(event.raw, platformWire)
            if (result != null) {
                val parsed = ParsedFieldsFactory.create(
                    result.shape ?: "notification", result.fields,
                )
                return Observation.Notification(
                    timestamp = event.raw.postTime,
                    captureId = null,
                    ruleId = result.ruleId,
                    metadata = metadataProvider.current(),
                    flow = result.flow,
                    modeHint = result.modeHint,
                    parsed = parsed,
                    target = result.intent,
                    effects = result.effects,
                    transitionOverrides = result.transitionOverrides,
                    expectedOutcomes = result.outcomes,
                )
            }
        }

        // Unknown notification — preserve raw text for future analysis
        val rawText = event.raw.toFullString()
        Timber.d("ObservationClassifier: UNKNOWN notification — $rawText")
        return Observation.Notification(
            timestamp = event.raw.postTime,
            captureId = null,
            ruleId = null,
            metadata = metadataProvider.current(),
            flow = null,
            modeHint = null,
            parsed = ParsedFields.NotificationFields(
                intent = "unknown",
                rawText = rawText,
            ),
            target = "UNKNOWN",
        )
    }
}
