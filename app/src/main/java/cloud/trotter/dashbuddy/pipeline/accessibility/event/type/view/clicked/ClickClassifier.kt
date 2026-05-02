package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.BuildConfig
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
 * The Kotlin implementation is authoritative. In debug builds the JSON interpreter runs
 * in parallel and logs any disagreement via [dualRunClick].
 */
class ClickClassifier @Inject constructor(
    private val interpreter: JsonRuleInterpreter,
) {

    fun classify(node: UiNode): Observation.Click {
        // Accepting an offer
        if (node.hasId("accept_button")) {
            val obs = makeClick("accept_offer", "doordash.click.accept_offer")
            dualRunClick(node, "AcceptOffer")
            return obs
        }

        // Declining an offer
        if (node.hasText("Decline offer")) {
            val obs = makeClick("decline_offer", "doordash.click.decline_offer")
            dualRunClick(node, "DeclineOffer")
            return obs
        }

        // Arrived at store (pickup navigation)
        if (node.hasId("primary_action_button") &&
            (node.hasText("Arrived at store") || node.hasText("Arrived"))
        ) {
            val obs = makeClick("arrived_at_store", "doordash.click.arrived_at_store")
            dualRunClick(node, "ArrivedAtStore")
            return obs
        }

        // Unknown — log node identity for future classification
        val nodeId = node.viewIdResourceName?.takeIf { it.isNotBlank() && it != "no_id" }
        val nodeText = node.text?.takeIf { it.isNotBlank() }
        Timber.d("ClickClassifier: UNKNOWN — id=$nodeId text=$nodeText")
        val obs = Observation.Click(
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
        dualRunClick(node, "Unknown")
        return obs
    }

    private fun makeClick(intent: String, ruleId: String): Observation.Click =
        Observation.Click(
            timestamp = System.currentTimeMillis(),
            captureId = null,
            ruleId = ruleId,
            metadata = ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = ParsedFields.ClickFields(intent = intent),
            target = intent.uppercase(),
        )

    /**
     * Debug-only: compare Kotlin classification with JSON interpreter result.
     */
    private fun dualRunClick(node: UiNode, kotlinType: String) {
        if (!BuildConfig.DEBUG) return
        val ruleset = interpreter.clickRuleset ?: return

        val jsonResult = ruleset.classifyFirst(node)
        val jsonType = jsonResult?.let { it::class.simpleName } ?: "Unknown(no-rule)"
        if (kotlinType != jsonType) {
            Timber.w(
                "MATCHER_DISAGREE [click] kotlin=$kotlinType json=$jsonType " +
                    "id=${node.viewIdResourceName} text=${node.text}"
            )
        }
    }
}
