package cloud.trotter.dashbuddy.domain.model.notification

/**
 * Raw notification payload extracted from a [android.service.notification.StatusBarNotification].
 * Analogous to [cloud.trotter.dashbuddy.domain.model.accessibility.UiNode] — this is the
 * uninterpreted source data; [NotificationInfo] carries the typed, parsed result.
 */
data class RawNotificationData(
    val title: String?,
    val text: String?,
    val tickerText: String?,
    val bigText: String?,
    val packageName: String,
    val postTime: Long,
    val isClearable: Boolean,
) {
    fun toFullString(): String =
        listOfNotNull(title, text, bigText, tickerText).joinToString(" | ")
}
