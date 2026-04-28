package cloud.trotter.dashbuddy.domain.model.notification

/**
 * Typed, parsed notification result — analogous to
 * [cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo].
 *
 * [NotificationClassifier] maps [RawNotificationData] to one of these subtypes.
 * Consumers (e.g. [NotificationHandler]) pattern-match on the sealed class
 * instead of re-scanning raw text.
 */
sealed class NotificationInfo {

    /**
     * A customer added a tip on a past delivery.
     *
     * @param amount      Tip amount in dollars (e.g. 5.00).
     * @param storeName   Store name from the notification (e.g. "H-E-B").
     * @param deliveredAt Human-readable delivery time string (e.g. "4/26, 3:15 PM").
     */
    data class AdditionalTip(
        val amount: Double,
        val storeName: String,
        val deliveredAt: String,
    ) : NotificationInfo()

    /**
     * A new delivery order is available. The offer screen handler drives state;
     * this carries no extra data.
     */
    data object NewOrder : NotificationInfo()

    /**
     * A scheduled dash reservation expired without being activated.
     */
    data object ScheduledDashExpired : NotificationInfo()

    /**
     * Notification could not be matched to a known type.
     * Raw text is preserved so unknown patterns can be analyzed and classified later.
     */
    data class Unknown(val rawText: String) : NotificationInfo()
}
