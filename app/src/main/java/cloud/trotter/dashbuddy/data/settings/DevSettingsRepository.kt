package cloud.trotter.dashbuddy.data.settings

import android.util.Log
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevSettingsRepository @Inject constructor(
    private val dataSource: DevSettingsDataSource
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ============================================================================================
    // IN-MEMORY SNAPSHOT STATES
    // ============================================================================================
    private val defaultSnapshotWhitelist =
        if (BuildConfig.DEBUG) Screen.entries.toSet() else emptySet()
    private val _snapshotWhitelist = MutableStateFlow(defaultSnapshotWhitelist)
    val snapshotWhitelist = _snapshotWhitelist.asStateFlow()

    private val _devSnapshotsEnabled = MutableStateFlow(BuildConfig.DEBUG)
    val devSnapshotsEnabled = _devSnapshotsEnabled.asStateFlow()

    fun toggleSnapshotScreen(screen: Screen, isEnabled: Boolean) {
        val current = _snapshotWhitelist.value.toMutableSet()
        if (isEnabled) current.add(screen) else current.remove(screen)
        _snapshotWhitelist.value = current
    }

    fun enableSensitiveSnapshots(enabled: Boolean) = toggleSnapshotScreen(Screen.SENSITIVE, enabled)

    // ============================================================================================
    // PERSISTED STREAMS
    // ============================================================================================
    val minLogLevel = dataSource.logLevel.map { level ->
        level ?: if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO
    }.stateIn(scope, SharingStarted.Eagerly, if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)

    val isDevModeUnlocked: Flow<Boolean> =
        dataSource.isDevModeUnlocked.map { it ?: BuildConfig.DEBUG }

    // ============================================================================================
    // WRITE ACTIONS
    // ============================================================================================
    suspend fun setDevModeUnlocked(unlocked: Boolean) =
        dataSource.update { it[DevSettingsDataSource.Keys.IS_DEV_MODE_UNLOCKED] = unlocked }

    suspend fun setLogLevel(priority: Int) =
        dataSource.update { it[DevSettingsDataSource.Keys.LOG_LEVEL] = priority }

    suspend fun clearPreferences() {
        Timber.w("Clearing Developer Preferences")
        dataSource.update { it.clear() }
    }
}