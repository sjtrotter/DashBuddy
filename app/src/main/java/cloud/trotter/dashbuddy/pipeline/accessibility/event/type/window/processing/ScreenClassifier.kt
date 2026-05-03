package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.RequestedAction
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenClassifier @Inject constructor(
    private val interpreter: JsonRuleInterpreter,
) {

    fun classify(node: UiNode): Observation.Screen {
        val ruleset = interpreter.screenRuleset
        if (ruleset == null) {
            Timber.w("ScreenClassifier: no ruleset loaded")
            return makeObservation("UNKNOWN", null, null, ParsedFields.None, null)
        }

        val result = ruleset.matchFirst(node)
        if (result == null) {
            Timber.i("SCREEN: UNKNOWN")
            return makeObservation("UNKNOWN", null, null, ParsedFields.None, null)
        }

        Timber.i("SCREEN: ${result.target}")
        if (result.actions.isNotEmpty()) {
            Timber.d("SCREEN: ${result.target} has ${result.actions.size} action(s)")
        }
        return makeObservation(
            screenName = result.target,
            flow = result.flow,
            modeHint = result.modeHint,
            parsed = result.parsed,
            ruleId = result.ruleId,
            actions = result.actions,
        )
    }

    private fun makeObservation(
        screenName: String,
        flow: cloud.trotter.dashbuddy.domain.state.Flow?,
        modeHint: cloud.trotter.dashbuddy.domain.state.Mode?,
        parsed: ParsedFields,
        ruleId: String?,
        actions: List<RequestedAction> = emptyList(),
    ): Observation.Screen {
        return Observation.Screen(
            timestamp = System.currentTimeMillis(),
            captureId = null,
            ruleId = ruleId,
            metadata = ReplayMetadata.EMPTY,
            flow = flow,
            modeHint = modeHint,
            parsed = parsed,
            target = screenName,
            actions = actions,
        )
    }
}
