package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import timber.log.Timber
import javax.inject.Inject

/**
 * Classifies a [RawNotificationData] into a typed [Observation.Notification].
 *
 * Delegates to the JSON rule interpreter — the ruleset is authoritative.
 */
class NotificationClassifier @Inject constructor(
    private val interpreter: JsonRuleInterpreter,
    private val metadataProvider: ReplayMetadataProvider,
) {

    fun classify(raw: RawNotificationData): Observation.Notification {
        val ruleset = interpreter.notificationRuleset
        if (ruleset != null) {
            val result = ruleset.classifyFirst(raw)
            if (result != null) {
                return Observation.Notification(
                    timestamp = System.currentTimeMillis(),
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
        val rawText = raw.toFullString()
        Timber.d("NotificationClassifier: UNKNOWN — $rawText")
        return Observation.Notification(
            timestamp = System.currentTimeMillis(),
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
