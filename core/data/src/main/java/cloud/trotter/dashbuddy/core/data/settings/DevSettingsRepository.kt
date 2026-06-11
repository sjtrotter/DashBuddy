package cloud.trotter.dashbuddy.core.data.settings

import android.util.Log
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
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
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Named

@Singleton
class DevSettingsRepository @Inject constructor(
    private val dataSource: DevSettingsDataSource,
    @param:Named("isDebug") private val isDebug: Boolean,
    @param:IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    // ============================================================================================
    // IN-MEMORY SNAPSHOT STATES
    // ============================================================================================
    // Empty whitelist = "capture all screens" in debug; non-empty = only listed screens
    private val _snapshotWhitelist = MutableStateFlow<Set<String>>(emptySet())
    val snapshotWhitelist = _snapshotWhitelist.asStateFlow()

    private val _devSnapshotsEnabled = MutableStateFlow(isDebug)
    val devSnapshotsEnabled = _devSnapshotsEnabled.asStateFlow()

    fun toggleSnapshotScreen(screenName: String, isEnabled: Boolean) {
        val current = _snapshotWhitelist.value.toMutableSet()
        if (isEnabled) current.add(screenName) else current.remove(screenName)
        _snapshotWhitelist.value = current
    }

    fun enableSensitiveSnapshots(enabled: Boolean) = toggleSnapshotScreen("SENSITIVE", enabled)

    // ============================================================================================
    // PERSISTED STREAMS
    // ============================================================================================
    val minLogLevel = dataSource.logLevel.map { level ->
        level ?: if (isDebug) Log.DEBUG else Log.INFO
    }.stateIn(scope, SharingStarted.Eagerly, if (isDebug) Log.DEBUG else Log.INFO)

    val isDevModeUnlocked: Flow<Boolean> =
        dataSource.isDevModeUnlocked.map { it ?: isDebug }

    // ============================================================================================
    // WRITE ACTIONS
    // ============================================================================================
    suspend fun setDevModeUnlocked(unlocked: Boolean) = dataSource.setDevModeUnlocked(unlocked)

    suspend fun setLogLevel(priority: Int) = dataSource.setLogLevel(priority)

    suspend fun clearPreferences() {
        Timber.Forest.w("Clearing Developer Preferences")
        dataSource.clear()
    }
}