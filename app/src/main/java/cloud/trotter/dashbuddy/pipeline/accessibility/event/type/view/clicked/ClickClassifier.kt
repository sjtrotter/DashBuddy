package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import timber.log.Timber
import javax.inject.Inject

/**
 * Classifies a clicked [UiNode] into a typed [ClickInfo] subtype.
 *
 * The Kotlin implementation is authoritative. In debug builds the JSON interpreter runs
 * in parallel and logs any disagreement via [dualRunClick].
 */
class ClickClassifier @Inject constructor(
    private val interpreter: JsonRuleInterpreter,
) {

    fun classify(node: UiNode): ClickInfo {

        // Accepting an offer
        if (node.hasId("accept_button")) {
            dualRunClick(node, ClickInfo.AcceptOffer)
            return ClickInfo.AcceptOffer
        }

        // Declining an offer
        if (node.hasText("Decline offer")) {
            dualRunClick(node, ClickInfo.DeclineOffer)
            return ClickInfo.DeclineOffer
        }

        // Arrived at store (pickup navigation)
        if (node.hasId("primary_action_button") &&
            (node.hasText("Arrived at store") || node.hasText("Arrived"))
        ) {
            dualRunClick(node, ClickInfo.ArrivedAtStore)
            return ClickInfo.ArrivedAtStore
        }

        // Unknown — log node identity for future classification
        val nodeId = node.viewIdResourceName?.takeIf { it.isNotBlank() && it != "no_id" }
        val nodeText = node.text?.takeIf { it.isNotBlank() }
        val result = ClickInfo.Unknown(nodeId = nodeId, nodeText = nodeText)
        Timber.d("ClickClassifier: UNKNOWN — id=$nodeId text=$nodeText")
        dualRunClick(node, result)
        return result
    }

    /**
     * Debug-only: compare Kotlin classification with JSON interpreter result.
     * JSON interpreter is never authoritative in this phase.
     */
    private fun dualRunClick(node: UiNode, kotlinResult: ClickInfo) {
        if (!BuildConfig.DEBUG) return
        val ruleset = interpreter.clickRuleset ?: return

        // For Unknown the JSON ruleset returns null (no rule matched) — that aligns correctly.
        val jsonResult = ruleset.classifyFirst(node)
        val kotlinType = kotlinResult::class.simpleName
        val jsonType = jsonResult?.let { it::class.simpleName } ?: "Unknown(no-rule)"
        if (kotlinType != jsonType) {
            Timber.w(
                "MATCHER_DISAGREE [click] kotlin=$kotlinType json=$jsonType " +
                    "id=${node.viewIdResourceName} text=${node.text}"
            )
        }
    }
}
