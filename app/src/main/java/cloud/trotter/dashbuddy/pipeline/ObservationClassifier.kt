package cloud.trotter.dashbuddy.pipeline

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.RequestedAction
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
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

    fun classify(event: PipelineEvent): Observation = when (event) {
        is PipelineEvent.Screen -> classifyScreen(event)
        is PipelineEvent.Click -> classifyClick(event)
        is PipelineEvent.Notification -> classifyNotification(event)
    }

    // ── Screen ──────────────────────────────────────────────────────────

    private fun classifyScreen(event: PipelineEvent.Screen): Observation.Screen {
        val ruleset = interpreter.screenRuleset
        if (ruleset == null) {
            Timber.w("ObservationClassifier: no screen ruleset loaded")
            return makeScreenObservation("UNKNOWN", null, null, ParsedFields.None, null)
        }

        val result = ruleset.matchFirst(event.tree)
        if (result == null) {
            Timber.i("SCREEN: UNKNOWN")
            return makeScreenObservation("UNKNOWN", null, null, ParsedFields.None, null)
        }

        Timber.i("SCREEN: ${result.target}")
        if (result.actions.isNotEmpty()) {
            Timber.d("SCREEN: ${result.target} has ${result.actions.size} action(s)")
        }

        val obs = makeScreenObservation(
            screenName = result.target,
            flow = result.flow,
            modeHint = result.modeHint,
            parsed = result.parsed,
            ruleId = result.ruleId,
            actions = result.actions,
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
        actions: List<RequestedAction> = emptyList(),
    ) = Observation.Screen(
        timestamp = System.currentTimeMillis(),
        captureId = null,
        ruleId = ruleId,
        metadata = metadataProvider.current(),
        flow = flow,
        modeHint = modeHint,
        parsed = parsed,
        target = screenName,
        actions = actions,
    )

    // ── Click ───────────────────────────────────────────────────────────

    private fun classifyClick(event: PipelineEvent.Click): Observation.Click {
        val ruleset = interpreter.clickRuleset
        if (ruleset != null) {
            val result = ruleset.classifyFirst(event.node)
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

    private fun classifyNotification(event: PipelineEvent.Notification): Observation.Notification {
        val ruleset = interpreter.notificationRuleset
        if (ruleset != null) {
            val result = ruleset.classifyFirst(event.raw)
            if (result != null) {
                return Observation.Notification(
                    timestamp = event.raw.postTime,
                    captureId = null,
                    ruleId = result.ruleId,
                    metadata = metadataProvider.current(),
                    flow = result.flow,
                    modeHint = result.modeHint,
                    parsed = ParsedFields.NotificationFields(
                        intent = result.intent,
                        amount = result.fields["amount"] as? Double,
                        storeName = result.fields["storeName"] as? String,
                        deliveredAt = result.fields["deliveredAt"] as? String,
                    ),
                    target = result.intent,
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
