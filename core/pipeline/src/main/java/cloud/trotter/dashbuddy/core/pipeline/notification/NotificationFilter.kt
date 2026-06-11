package cloud.trotter.dashbuddy.core.pipeline.notification

import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Package pre-filter. Gates notifications before they reach the rule engine.
 *
 * All notifications from watched packages pass through — unwanted ongoing
 * notifications are handled downstream via `"shape": "noise"` rules.
 *
 * Stateless (#356): reads the shared enabled-packages StateFlow at the gate.
 * No private cache, no blocking init on the hot path, nothing to refresh.
 */
@Singleton
class NotificationFilter @Inject constructor(
    private val platformPreferences: PlatformPreferences,
) {
    fun isRelevant(raw: RawNotificationData): Boolean =
        raw.packageName in platformPreferences.enabledPackages.value
}
