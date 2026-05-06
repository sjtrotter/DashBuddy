package cloud.trotter.dashbuddy.core.data.settings

import android.content.Context
import android.content.pm.PackageManager
import cloud.trotter.dashbuddy.core.datastore.settings.PlatformPreferencesDataSource
import cloud.trotter.dashbuddy.domain.state.Platform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlatformPreferencesRepository @Inject constructor(
    private val dataSource: PlatformPreferencesDataSource,
    @param:ApplicationContext private val context: Context,
) {
    private val packageManager: PackageManager = context.packageManager

    /** Flow of enabled Platform enum values. Defaults to installed apps if no preference saved. */
    val enabledPlatforms: Flow<Set<Platform>> = dataSource.enabledPlatforms.map { saved ->
        if (saved != null) {
            saved.mapNotNull { Platform.fromWire(it) }.toSet()
        } else {
            detectInstalledPlatforms()
        }
    }

    /** Flow of enabled package names (for listener filtering). */
    val enabledPackages: Flow<Set<String>> = enabledPlatforms.map { platforms ->
        platforms.mapNotNull { it.packageName }.toSet()
    }

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

    /** Toggle a single platform on or off. Persists to DataStore. */
    suspend fun setEnabled(platform: Platform, enabled: Boolean) {
        val current = currentEnabledWireNames()
        val updated = if (enabled) {
            current + platform.wire
        } else {
            current - platform.wire
        }
        dataSource.setEnabledPlatforms(updated)
    }

    private suspend fun currentEnabledWireNames(): Set<String> {
        val saved = dataSource.enabledPlatforms.first()
        return saved ?: detectInstalledPlatforms().map { it.wire }.toSet()
    }
}
