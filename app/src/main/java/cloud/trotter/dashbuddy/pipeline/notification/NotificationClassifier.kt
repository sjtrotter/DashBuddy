package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import timber.log.Timber
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Classifies a [RawNotificationData] into a typed [NotificationInfo] subtype.
 *
 * Analogous to [ScreenParser] in the window pipeline — consumes raw data,
 * returns a strongly-typed result so consumers can pattern-match instead of
 * re-scanning text.
 *
 * Classification priority (first match wins):
 *   1. AdditionalTip  — "added $X.XX tip on a past [store] order delivered at …"
 *   2. NewOrder       — "New Order" in title
 *   3. ScheduledDashExpired — "scheduled" + "expired" in text
 *   4. Unknown        — everything else; raw text preserved for future analysis
 */
class NotificationClassifier @Inject constructor() {

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
            val amountStr = tipMatcher.group(1) ?: return unknown(fullText)
            val storeName = tipMatcher.group(2) ?: return unknown(fullText)
            val deliveredAt = tipMatcher.group(3) ?: return unknown(fullText)
            return try {
                NotificationInfo.AdditionalTip(
                    amount = amountStr.toDouble(),
                    storeName = storeName.trim(),
                    deliveredAt = deliveredAt
                )
            } catch (e: NumberFormatException) {
                Timber.w("NotificationClassifier: could not parse tip amount '$amountStr'")
                unknown(fullText)
            }
        }

        // 2. New order
        val title = raw.title.orEmpty()
        if (title.contains("new order", ignoreCase = true)) {
            return NotificationInfo.NewOrder
        }

        // 3. Scheduled dash expired
        if (fullText.contains("scheduled", ignoreCase = true) &&
            fullText.contains("expired", ignoreCase = true)
        ) {
            return NotificationInfo.ScheduledDashExpired
        }

        // 4. Unknown — preserve raw text for later analysis
        return unknown(fullText)
    }

    private fun unknown(rawText: String): NotificationInfo.Unknown {
        Timber.d("NotificationClassifier: UNKNOWN — $rawText")
        return NotificationInfo.Unknown(rawText)
    }
}
