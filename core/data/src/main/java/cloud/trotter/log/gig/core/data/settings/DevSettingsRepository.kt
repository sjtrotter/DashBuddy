package cloud.trotter.log.gig.core.data.settings

import cloud.trotter.log.gig.core.data.census.CensusEnrolSchemas
import cloud.trotter.log.gig.domain.census.CensusEnrolRejectReason
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure
import cloud.trotter.log.gig.domain.census.CensusChoices
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.census.CensusConsentReceipt
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.census.CensusLastRun
import cloud.trotter.log.gig.domain.census.CensusRunOutcome
import cloud.trotter.log.gig.domain.census.CensusUploadPreferences
import cloud.trotter.log.gig.domain.offer.story.AddOnSpeech
import android.util.Log
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.domain.model.bubble.BubbleSessionMode
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import cloud.trotter.census.contract.SkeletonSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.log.gig.domain.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Named

@Singleton
class DevSettingsRepository @Inject constructor(
    private val dataSource: DevSettingsDataSource,
    @param:Named("isDebug") private val isDebug: Boolean,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
    @param:Named("appVersionName") private val appVersion: String,
) : CensusUploadPreferences {
    val censusShareCaptures: Flow<Boolean> = dataSource.censusShareCaptures.map { isDebug && it }
    val censusBaseUrl: Flow<String> = dataSource.censusBaseUrl

    /** #1621: the census decision in every build, bound to the endpoint ([CensusConsent.of]); the opt-in shows it. */
    val censusConsent: Flow<CensusConsent> = combine(dataSource.censusConsent, censusBaseUrl) { raw, endpoint ->
        CensusConsent.of(raw.switchOn, raw.receipt, endpoint)
    }

    /**
     * What every upload stage obeys, in every build (#1620): [censusConsent] ON, so no receipt or a receipt for another
     * endpoint stops uploads.
     */
    val censusUploadEnabled: Flow<Boolean> = censusConsent.map { it == CensusConsent.ON }
    override val enabled: Flow<Boolean> = censusUploadEnabled
    override val baseUrl: Flow<String> = censusBaseUrl
    val nextAllowedAtMillis = dataSource.nextAllowedAtMillis
    val censusPolicy = dataSource.censusPolicy
    override val acceptedSchemaIds: Flow<Set<String>> = dataSource.censusAcceptedSchemaIds.map {
        it ?: setOf(SkeletonSchema.SCHEMA_ID)
    }
    /**
     * The test (debug) build: its census also offers capture sharing and the developer tools (Upload now, the identity
     * reset), and its disclosure carries the test build's note. The census itself runs the same in every build (#1620).
     */
    val censusTestBuild: Boolean get() = isDebug

    /** #1621: the census choices; the sink queues only the platforms they cover and Wi-Fi only sets the network (#1620). */
    val censusChoices: Flow<CensusChoices> = combine(dataSource.censusPlatforms, dataSource.censusWifiOnly) { wires, wifiOnly ->
        CensusChoices(wires?.mapNotNull(Platform::fromWire)?.toSet(), wifiOnly)
    }
    override val choices: Flow<CensusChoices> = censusChoices

    /** #1620: when the daily census run is due, drawn by [cloud.trotter.log.gig.core.data.census.CensusUploadPlanner]. */
    val censusNextRunAtMillis: Flow<Long?> = dataSource.censusNextRunAtMillis
    suspend fun setCensusNextRunAtMillis(value: Long) = dataSource.setCensusNextRunAtMillis(value)
    /** A daily run consumes its target before it sends, so no retry or left-over work matches it again. */
    suspend fun clearCensusNextRunAtMillis() = dataSource.clearCensusNextRunAtMillis()
    /** #1622: the last census deletion request; an unreadable state decodes as pending (fail closed). */
    val censusDeletion: Flow<CensusDeletion?> = dataSource.censusDeletion.map { raw ->
        raw?.let {
            CensusDeletion(it.requestedAtMillis, CensusDeletion.State.fromWire(it.stateWire), it.rows, it.deletedDay,
                it.localCleared, it.installId, CensusDeletion.Waiting.fromWire(it.waitingWire))
        }
    }
    suspend fun setCensusDeletion(value: CensusDeletion) = dataSource.setCensusDeletion(
        DevSettingsDataSource.RawCensusDeletion(
            value.requestedAtMillis, value.state.wire, value.rows, value.deletedDay, value.localCleared,
            value.installId, value.waiting?.wire,
        ),
    )
    val censusLastRun: Flow<CensusLastRun?> = dataSource.censusLastRun.map { raw ->
        raw?.let { (at, wire, detail, envelopes, reason) ->
            CensusLastRun(at, CensusRunOutcome.fromWire(wire), detail, envelopes, CensusEnrolRejectReason.fromWire(reason))
        }
    }
    suspend fun setCensusLastRun(run: CensusLastRun) =
        dataSource.setCensusLastRun(run.atMillis, run.outcome.wire, run.detail, run.envelopesPosted, run.reason?.wire)
    suspend fun recordCensusReset(run: CensusLastRun) = dataSource.recordCensusReset(run.atMillis, run.outcome.wire)

    /**
     * The census switch, in any build. On is the disclosure's Accept: it records a granted receipt bound to the current
     * endpoint and saves [choices] with it, in one edit. Off without a receipt is a server revoke; the dasher's own off is
     * [declineCensus]. Consent OFF also turns capture sharing OFF; the data source enforces this atomically.
     */
    suspend fun setCensusUploadEnabled(enabled: Boolean, choices: CensusChoices? = null) {
        val receipt = if (enabled) censusReceipt(granted = true) else null
        dataSource.setCensusUploadEnabled(enabled, receipt, choices?.platforms?.map { it.wire }?.toSet(), choices?.wifiOnly)
    }

    /** The dasher's decline (Not now, or the switch turned off): off, with a declined receipt. Only an Accept turns it on. */
    suspend fun declineCensus() = dataSource.setCensusUploadEnabled(false, censusReceipt(granted = false))

    suspend fun setCensusWifiOnly(wifiOnly: Boolean) = dataSource.setCensusWifiOnly(wifiOnly)
    /** One platform in or out of the census, applied inside the store's edit; [readable] is the unsaved default. */
    suspend fun setCensusPlatform(platform: Platform, covered: Boolean, readable: List<Platform>) =
        dataSource.setCensusPlatform(platform.wire, covered, readable.map { it.wire }.toSet())

    private suspend fun censusReceipt(granted: Boolean) = CensusConsentReceipt(
        ConsentReceipt(System.currentTimeMillis(), appVersion, PrivacyDisclosure.REVISION, granted),
        endpoint = censusBaseUrl.first(),
    )

    suspend fun setCensusShareCaptures(enabled: Boolean) = dataSource.setCensusShareCaptures(isDebug && enabled)
    suspend fun setNextAllowedAtMillis(value: Long) = dataSource.setNextAllowedAtMillis(value)
    suspend fun setCensusPolicy(value: JsonObject) = dataSource.setCensusPolicy(
        value.mapNotNull { (key, element) ->
            val number = element as? JsonPrimitive
            number?.takeUnless { it.isString }?.intOrNull?.let { key to it }
        }.toMap(),
        CensusEnrolSchemas.acceptedIn(value),
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
    val addOnSpeech = dataSource.addOnSpeech.map { wire ->
        AddOnSpeech.entries.firstOrNull { it.name == wire } ?: AddOnSpeech.JOB_TOTALS
    }.stateIn(scope, SharingStarted.Eagerly, AddOnSpeech.JOB_TOTALS)

    suspend fun setAddOnSpeech(value: AddOnSpeech) = dataSource.setAddOnSpeech(value.name)

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