package cloud.trotter.dashbuddy.core.data.settings

import android.content.Context
import android.content.pm.PackageManager
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.core.datastore.settings.PlatformPreferencesDataSource
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlatformPreferencesRepository @Inject constructor(
    private val dataSource: PlatformPreferencesDataSource,
    @param:ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
) : PlatformPreferences {
    private val packageManager: PackageManager = context.packageManager

    /**
     * THE single materialization of the enabled-platforms preference (#356).
     * Eagerly shared for the app's lifetime so every consumer — listeners,
     * filters, pipelines, UI — reads one always-current value instead of
     * holding a private cache. Defaults to installed apps if nothing saved.
     */
    override val enabledPlatforms: StateFlow<Set<Platform>> = dataSource.enabledPlatforms
        .map { saved ->
            if (saved != null) {
                saved.mapNotNull { Platform.fromWire(it) }.toSet()
            } else {
                detectInstalledPlatforms()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, detectInstalledPlatforms())

    /** Package names of the enabled platforms (listener-level event filtering). */
    override val enabledPackages: StateFlow<Set<String>> = enabledPlatforms
        .map { platforms -> platforms.mapNotNull { it.packageName }.toSet() }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            enabledPlatforms.value.mapNotNull { it.packageName }.toSet(),
        )

    /** Check which supported platforms are installed on device. */
    fun detectInstalledPlatforms(): Set<Platform> =
        Platform.entries
            .filter { it.packageName != null }
            .filter { platform ->
                try {
                    packageManager.getPackageInfo(platform.packageName!!, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
            .toSet()

    /** Toggle a single platform on or off — one atomic DataStore edit (#364). */
    suspend fun setEnabled(platform: Platform, enabled: Boolean) {
        dataSource.updateEnabledPlatforms { saved ->
            val current = saved ?: detectInstalledPlatforms().map { it.wire }.toSet()
            if (enabled) current + platform.wire else current - platform.wire
        }
    }
}
