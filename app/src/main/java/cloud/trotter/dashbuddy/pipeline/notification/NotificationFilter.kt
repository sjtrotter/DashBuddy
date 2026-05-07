package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.core.data.settings.PlatformPreferencesRepository
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Package + clearability pre-filter. Gates notifications before they reach the rule engine.
 *
 * By default, ongoing (non-clearable) notifications are dropped to avoid noise from
 * persistent foreground-service notifications. Platforms can opt in to ongoing notifications
 * by declaring `"allowOngoing": true` in their pipeline config header.
 */
@Singleton
class NotificationFilter @Inject constructor(
    private val platformPreferences: PlatformPreferencesRepository,
    private val interpreter: JsonRuleInterpreter,
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
        if (raw.packageName !in cachedPackages) return false

        // If not clearable, check if the platform opts in to ongoing notifications
        if (!raw.isClearable && !allowsOngoing(raw.packageName)) return false
        return true
    }

    /** Called by the pipeline to update the cached set when preferences change. */
    fun updateEnabledPackages(packages: Set<String>) {
        cachedPackages = packages
        initialized = true
    }

    private fun allowsOngoing(packageName: String): Boolean {
        val platform = Platform.fromPackage(packageName)
        return interpreter.getPipelineConfig(platform.wire, "notification")?.allowOngoing == true
    }
}
