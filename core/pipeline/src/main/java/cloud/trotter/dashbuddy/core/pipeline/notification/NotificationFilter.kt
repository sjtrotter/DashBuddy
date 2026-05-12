package cloud.trotter.dashbuddy.core.pipeline.notification

import cloud.trotter.dashbuddy.core.data.settings.PlatformPreferencesRepository
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Package pre-filter. Gates notifications before they reach the rule engine.
 *
 * All notifications from watched packages pass through — unwanted ongoing
 * notifications are handled downstream via `"shape": "noise"` rules.
 */
@Singleton
class NotificationFilter @Inject constructor(
    private val platformPreferences: PlatformPreferencesRepository,
) {
    /** Cached enabled packages — refreshed lazily. */
    @Volatile
    private var cachedPackages: Set<String> = Platform.watchedPackages()

    @Volatile
    private var initialized = false

    fun isRelevant(raw: RawNotificationData): Boolean {
        if (!initialized) {
            cachedPackages = runBlocking { platformPreferences.enabledPackages.first() }
            initialized = true
        }
        return raw.packageName in cachedPackages
    }

    /** Called by the pipeline to update the cached set when preferences change. */
    fun updateEnabledPackages(packages: Set<String>) {
        cachedPackages = packages
        initialized = true
    }
}
