package cloud.trotter.dashbuddy.services.accessibility.notification

import android.app.Notification
import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.log.Logger as Log

object NotificationParser {
    private const val TAG = "NotificationParser"

    fun parse(event: AccessibilityEvent): NotificationInfo? {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return null

        // Log raw text list first (legacy fallback)
        val legacyText = event.text.joinToString(" ")

        try {
            val parcelable = event.parcelableData

            if (parcelable is Notification) {
                val extras = parcelable.extras ?: return null

                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

                Log.d(
                    TAG,
                    "Parsing Notification -> Title: '$title', Text: '$text', BigText: '$bigText'"
                )

                // If completely empty, try legacy
                if (title.isBlank() && text.isBlank() && bigText.isNullOrBlank()) {
                    if (legacyText.isNotBlank()) {
                        return NotificationInfo(
                            "",
                            legacyText,
                            null,
                            event.packageName?.toString() ?: "",
                            event.eventTime
                        )
                    }
                    return null
                }

                return NotificationInfo(
                    title = title,
                    text = text,
                    bigText = bigText,
                    packageName = event.packageName?.toString() ?: "",
                    timestamp = event.eventTime
                )
            } else {
                Log.w(
                    TAG,
                    "Event parcelable was NOT a Notification object. Type: ${parcelable?.javaClass?.name}"
                )
            }

            // Fallback to legacy text if parcelable failed
            if (legacyText.isNotBlank()) {
                Log.d(TAG, "Fallback to Legacy Text: $legacyText")
                return NotificationInfo(
                    title = "",
                    text = legacyText,
                    bigText = null,
                    packageName = event.packageName?.toString() ?: "",
                    timestamp = event.eventTime
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse notification", e)
        }

        return null
    }
}