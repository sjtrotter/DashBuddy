package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import timber.log.Timber
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Classifies a [RawNotificationData] into a typed [Observation.Notification].
 *
 * The Kotlin implementation is authoritative. In debug builds the JSON interpreter runs
 * in parallel and logs any disagreement via [dualRunNotification].
 *
 * Classification priority (first match wins):
 *   1. AdditionalTip  — "added $X.XX tip on a past [store] order delivered at …"
 *   2. NewOrder       — "New Order" in title
 *   3. ScheduledDashExpired — "scheduled" + "expired" in text
 *   4. Unknown        — everything else; raw text preserved for future analysis
 */
class NotificationClassifier @Inject constructor(
    private val interpreter: JsonRuleInterpreter,
) {

    // e.g. "added $5.00 tip on a past H-E-B order delivered at 4/26, 3:15 PM"
    private val tipPattern: Pattern = Pattern.compile(
        """added \$(\d+\.\d{2}) tip on a past (.+?) order delivered at (\d{1,2}/\d{1,2}, \d{1,2}:\d{2} [AP]M)""",
        Pattern.CASE_INSENSITIVE
    )

    fun classify(raw: RawNotificationData): Observation.Notification {
        val fullText = raw.toFullString()

        // 1. Additional tip
        val tipMatcher = tipPattern.matcher(fullText)
        if (tipMatcher.find()) {
            val amountStr = tipMatcher.group(1) ?: return unknown(raw, fullText)
            val storeName = tipMatcher.group(2) ?: return unknown(raw, fullText)
            val deliveredAt = tipMatcher.group(3) ?: return unknown(raw, fullText)
            val result = try {
                makeNotification(
                    "additional_tip",
                    "doordash.notification.additional_tip",
                    ParsedFields.ClickFields(
                        intent = "additional_tip",
                        nodeText = "$amountStr|$storeName|$deliveredAt",
                    ),
                )
            } catch (e: NumberFormatException) {
                Timber.w("NotificationClassifier: could not parse tip amount '$amountStr'")
                return unknown(raw, fullText)
            }
            dualRunNotification(raw, "AdditionalTip")
            return result
        }

        // 2. New order
        val title = raw.title.orEmpty()
        if (title.contains("new order", ignoreCase = true)) {
            val obs = makeNotification("new_order", "doordash.notification.new_order")
            dualRunNotification(raw, "NewOrder")
            return obs
        }

        // 3. Scheduled dash expired
        if (fullText.contains("scheduled", ignoreCase = true) &&
            fullText.contains("expired", ignoreCase = true)
        ) {
            val obs = makeNotification("scheduled_dash_expired", "doordash.notification.scheduled_dash_expired")
            dualRunNotification(raw, "ScheduledDashExpired")
            return obs
        }

        // 4. Unknown — preserve raw text for later analysis
        return unknown(raw, fullText)
    }

    private fun makeNotification(
        target: String,
        ruleId: String,
        parsed: ParsedFields = ParsedFields.ClickFields(intent = target),
    ): Observation.Notification = Observation.Notification(
        timestamp = System.currentTimeMillis(),
        captureId = null,
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = parsed,
        target = target.uppercase(),
    )

    private fun unknown(raw: RawNotificationData, rawText: String): Observation.Notification {
        Timber.d("NotificationClassifier: UNKNOWN — $rawText")
        val obs = Observation.Notification(
            timestamp = System.currentTimeMillis(),
            captureId = null,
            ruleId = null,
            metadata = ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = ParsedFields.ClickFields(
                intent = "unknown",
                nodeText = rawText,
            ),
            target = "UNKNOWN",
        )
        dualRunNotification(raw, "Unknown")
        return obs
    }

    /**
     * Debug-only: compare Kotlin classification with JSON interpreter result.
     */
    private fun dualRunNotification(raw: RawNotificationData, kotlinType: String) {
        if (!BuildConfig.DEBUG) return
        val ruleset = interpreter.notificationRuleset ?: return

        val jsonResult = ruleset.classifyFirst(raw)
        val jsonType = jsonResult?.let { it::class.simpleName } ?: "Unknown(no-rule)"
        if (kotlinType != jsonType) {
            Timber.w(
                "MATCHER_DISAGREE [notification] kotlin=$kotlinType json=$jsonType " +
                    "text=${raw.toFullString().take(120)}"
            )
        }
    }
}
