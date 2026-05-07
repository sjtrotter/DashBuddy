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
    val subText: String? = null,
    val packageName: String,
    val postTime: Long,
    val isClearable: Boolean,
    val isOngoing: Boolean = false,
    val category: String? = null,
    val channelId: String? = null,
    val actionLabels: List<String> = emptyList(),
) {
    fun toFullString(): String =
        listOfNotNull(title, text, bigText, tickerText, subText).joinToString(" | ")

    /** Content hash for CaptureBus dedup — identical text content deduplicates per session. */
    val contentHash: Int get() = toFullString().hashCode()
}
