package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import timber.log.Timber
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Classifies a [RawNotificationData] into a typed [NotificationInfo] subtype.
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

    fun classify(raw: RawNotificationData): NotificationInfo {
        val fullText = raw.toFullString()

        // 1. Additional tip
        val tipMatcher = tipPattern.matcher(fullText)
        if (tipMatcher.find()) {
            val amountStr = tipMatcher.group(1) ?: return unknown(raw, fullText)
            val storeName = tipMatcher.group(2) ?: return unknown(raw, fullText)
            val deliveredAt = tipMatcher.group(3) ?: return unknown(raw, fullText)
            val result = try {
                NotificationInfo.AdditionalTip(
                    amount = amountStr.toDouble(),
                    storeName = storeName.trim(),
                    deliveredAt = deliveredAt
                )
            } catch (e: NumberFormatException) {
                Timber.w("NotificationClassifier: could not parse tip amount '$amountStr'")
                unknown(raw, fullText)
            }
            dualRunNotification(raw, result)
            return result
        }

        // 2. New order
        val title = raw.title.orEmpty()
        if (title.contains("new order", ignoreCase = true)) {
            dualRunNotification(raw, NotificationInfo.NewOrder)
            return NotificationInfo.NewOrder
        }

        // 3. Scheduled dash expired
        if (fullText.contains("scheduled", ignoreCase = true) &&
            fullText.contains("expired", ignoreCase = true)
        ) {
            dualRunNotification(raw, NotificationInfo.ScheduledDashExpired)
            return NotificationInfo.ScheduledDashExpired
        }

        // 4. Unknown — preserve raw text for later analysis
        return unknown(raw, fullText)
    }

    private fun unknown(raw: RawNotificationData, rawText: String): NotificationInfo.Unknown {
        Timber.d("NotificationClassifier: UNKNOWN — $rawText")
        val result = NotificationInfo.Unknown(rawText)
        dualRunNotification(raw, result)
        return result
    }

    /**
     * Debug-only: compare Kotlin classification with JSON interpreter result.
     * JSON interpreter is never authoritative in this phase.
     */
    private fun dualRunNotification(raw: RawNotificationData, kotlinResult: NotificationInfo) {
        if (!BuildConfig.DEBUG) return
        val ruleset = interpreter.notificationRuleset ?: return

        val jsonResult = ruleset.classifyFirst(raw)
        val kotlinType = kotlinResult::class.simpleName
        val jsonType = jsonResult?.let { it::class.simpleName } ?: "Unknown(no-rule)"
        if (kotlinType != jsonType) {
            Timber.w(
                "MATCHER_DISAGREE [notification] kotlin=$kotlinType json=$jsonType " +
                    "text=${raw.toFullString().take(120)}"
            )
        }
    }
}
