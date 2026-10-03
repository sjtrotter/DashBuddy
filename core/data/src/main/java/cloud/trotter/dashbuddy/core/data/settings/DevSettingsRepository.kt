package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.domain.census.CensusLastRun
import cloud.trotter.dashbuddy.domain.census.CensusRunOutcome
import cloud.trotter.dashbuddy.domain.census.CensusUploadPreferences
import android.util.Log
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.dashbuddy.domain.model.bubble.BubbleSessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : CensusUploadPreferences {
    val censusUploadEnabled: Flow<Boolean> = dataSource.censusUploadEnabled.map { isDebug && it }
    val censusBaseUrl: Flow<String> = dataSource.censusBaseUrl
    override val enabled: Flow<Boolean> = censusUploadEnabled
    override val baseUrl: Flow<String> = censusBaseUrl
    val nextAllowedAtMillis = dataSource.nextAllowedAtMillis
    val censusPolicy = dataSource.censusPolicy
    val censusAvailable: Boolean get() = isDebug
    val censusLastRun: Flow<CensusLastRun?> = dataSource.censusLastRun.map { raw ->
        raw?.let { (at, wire, detail) -> CensusLastRun(at, CensusRunOutcome.fromWire(wire), detail) }
    }
    suspend fun setCensusLastRun(run: CensusLastRun) = dataSource.setCensusLastRun(run.atMillis, run.outcome.wire, run.detail)
    suspend fun recordCensusReset(run: CensusLastRun) = dataSource.recordCensusReset(run.atMillis, run.outcome.wire)

    suspend fun setCensusUploadEnabled(enabled: Boolean) = dataSource.setCensusUploadEnabled(isDebug && enabled)
    suspend fun setNextAllowedAtMillis(value: Long) = dataSource.setNextAllowedAtMillis(value)
    suspend fun setCensusPolicy(value: JsonObject) = dataSource.setCensusPolicy(
        value.mapNotNull { (key, element) ->
            val number = element as? JsonPrimitive
            number?.takeUnless { it.isString }?.intOrNull?.let { key to it }
        }.toMap(),
    )

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

    /**
     * The bubble's session-presentation experiment switch (#867). Decode is **fail-closed**: an
     * absent or unrecognized stored value resolves to [BubbleSessionMode.Default] — the shipped
     * follow-last-active presentation — so a corrupt/stale preference can never leave the HUD in an
     * undefined presentation.
     */
    val bubbleSessionMode: Flow<BubbleSessionMode> =
        dataSource.bubbleSessionMode.map { BubbleSessionMode.fromWire(it) }

    // ============================================================================================
    // WRITE ACTIONS
    // ============================================================================================
    suspend fun setDevModeUnlocked(unlocked: Boolean) = dataSource.setDevModeUnlocked(unlocked)

    suspend fun setLogLevel(priority: Int) = dataSource.setLogLevel(priority)

    /** #867 — persist the bubble session-presentation mode as its wire value. */
    suspend fun setBubbleSessionMode(mode: BubbleSessionMode) =
        dataSource.setBubbleSessionMode(mode.wire)

    suspend fun clearPreferences() {
        Timber.tag(TAG).w("Clearing Developer Preferences")
        dataSource.clear()
    }

    private companion object {
        private const val TAG = "DevSettings"
    }
}