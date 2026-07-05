package cloud.trotter.dashbuddy.core.pipeline.notification.mapper

import android.service.notification.StatusBarNotification
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import timber.log.Timber

/**
 * Map a [StatusBarNotification] to the domain [RawNotificationData], or null if
 * reading it throws.
 *
 * The mapping reads `notification.extras` and unwraps `getCharSequence(...)`
 * (#590). Those extras are attacker-influenced untrusted input: a malformed or
 * custom-Parcelable extra unparcels LAZILY on first read and can throw
 * (`BadParcelableException`) — the textbook notification-listener crash class. A
 * throw here propagates up the notification pipeline flow and kills the listener
 * (the same crash class #430's supervision closed on the accessibility side). So
 * this is made TOTAL: any failure drops that one notification (return null; the
 * pipeline's `mapNotNull` skips it) and the flow keeps emitting. One WARN fires —
 * a defended invariant, no raw notification text (Principle 7).
 */
fun StatusBarNotification.toDomain(): RawNotificationData? = try {
    val extras = this.notification.extras
    RawNotificationData(
        packageName = this.packageName,
        title = extras.getCharSequence("android.title")?.toString(),
        text = extras.getCharSequence("android.text")?.toString(),
        bigText = extras.getCharSequence("android.bigText")?.toString(),
        tickerText = this.notification.tickerText?.toString(),
        subText = extras.getCharSequence("android.subText")?.toString(),
        postTime = this.postTime,
        isClearable = this.isClearable,
        isOngoing = this.isOngoing,
        category = this.notification.category,
        channelId = this.notification.channelId,
        actionLabels = this.notification.actions
            ?.mapNotNull { it.title?.toString() } ?: emptyList(),
    )
} catch (e: Exception) {
    // Untrusted extras threw (e.g. BadParcelableException on lazy unparcel).
    // Drop this notification; keep the listener flow alive (parity with #430).
    Timber.tag("Pipeline").w("Dropped a notification: reading its extras threw ${e.javaClass.simpleName} (#590)")
    null
}
