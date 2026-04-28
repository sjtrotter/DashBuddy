package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import javax.inject.Inject

/**
 * Package-only pre-filter. The [NotificationListener] already gates on package, but this
 * provides defense-in-depth if [RawNotificationData] is ever injected from another path.
 *
 * Content-based filtering no longer happens here — the [NotificationClassifier] handles
 * classification and the [NotificationInfo.Unknown] type captures anything unrecognized.
 */
class NotificationFilter @Inject constructor() {

    fun isRelevant(raw: RawNotificationData): Boolean =
        raw.packageName == "com.doordash.driverapp"
}
