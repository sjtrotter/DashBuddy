package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import timber.log.Timber
import javax.inject.Inject

/**
 * Classifies a clicked [UiNode] into a typed [Observation.Click].
 *
 * Delegates to the JSON rule interpreter — the ruleset is authoritative.
 */
class ClickClassifier @Inject constructor(
    private val interpreter: JsonRuleInterpreter,
) {

    fun classify(node: UiNode): Observation.Click {
        val ruleset = interpreter.clickRuleset
        if (ruleset != null) {
            val result = ruleset.classifyFirst(node)
            if (result != null) {
                return Observation.Click(
                    timestamp = System.currentTimeMillis(),
                    captureId = null,
                    ruleId = result.ruleId,
                    metadata = ReplayMetadata.EMPTY,
                    flow = result.flow,
                    modeHint = result.modeHint,
                    parsed = ParsedFields.ClickFields(intent = result.intent),
                    target = result.intent,
                )
            }
        }

        // Unknown click — preserve node details for future classification
        val nodeId = node.viewIdResourceName?.takeIf { it.isNotBlank() && it != "no_id" }
        val nodeText = node.text?.takeIf { it.isNotBlank() }
        Timber.d("ClickClassifier: UNKNOWN — id=$nodeId text=$nodeText")
        return Observation.Click(
            timestamp = System.currentTimeMillis(),
            captureId = null,
            ruleId = null,
            metadata = ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = ParsedFields.ClickFields(
                intent = "unknown",
                nodeId = nodeId,
                nodeText = nodeText,
            ),
            target = "UNKNOWN",
        )
    }
}
