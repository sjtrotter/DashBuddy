package cloud.trotter.log.gig.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import cloud.trotter.log.gig.core.datastore.capability.ConsentReceiptJson
import cloud.trotter.log.gig.core.datastore.di.DevSettingsPreferences
import cloud.trotter.log.gig.domain.census.CensusChoices
import cloud.trotter.log.gig.domain.census.CensusConsentReceipt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevSettingsDataSource @Inject constructor(
    @param:DevSettingsPreferences private val ds: DataStore<Preferences>
) {
    private object Keys {
        val ADD_ON_SPEECH = stringPreferencesKey("add_on_speech")
        val IS_DEV_MODE_UNLOCKED = booleanPreferencesKey("is_dev_mode_unlocked")
        val LOG_LEVEL = intPreferencesKey("log_level")
        val CENSUS_SHARE_CAPTURES = booleanPreferencesKey("census_share_captures")
        val CENSUS_UPLOAD_ENABLED = booleanPreferencesKey("census_upload_enabled")
        val CENSUS_CONSENT_RECEIPT = stringPreferencesKey("census_consent_receipt_json")
        val CENSUS_CONSENT_SEQ = longPreferencesKey("census_consent_seq")
        val CENSUS_WIFI_ONLY = booleanPreferencesKey("census_wifi_only")
        val CENSUS_PLATFORMS = stringSetPreferencesKey("census_platforms")
        val CENSUS_NEXT_ALLOWED_AT = longPreferencesKey("census_next_allowed_at_millis")
        val CENSUS_NEXT_RUN_AT = longPreferencesKey("census_next_run_at_millis")
        val CENSUS_DELETION_AT = longPreferencesKey("census_deletion_requested_at_millis")
        val CENSUS_DELETION_STATE = stringPreferencesKey("census_deletion_state")
        val CENSUS_DELETION_LOCAL_CLEARED = booleanPreferencesKey("census_deletion_local_cleared")
        val CENSUS_DELETION_INSTALL_ID = stringPreferencesKey("census_deletion_install_id")
        val CENSUS_DELETION_ROWS = intPreferencesKey("census_deletion_rows")
        val CENSUS_DELETION_DAY = stringPreferencesKey("census_deletion_day")
        val CENSUS_DELETION_WAITING = stringPreferencesKey("census_deletion_waiting")
        val CENSUS_LAST_RUN_AT = longPreferencesKey("census_last_run_at_millis")
        val CENSUS_LAST_RUN_OUTCOME = stringPreferencesKey("census_last_run_outcome")
        val CENSUS_LAST_RUN_ENVELOPES = intPreferencesKey("census_last_run_envelopes")
        val CENSUS_LAST_RUN_DETAIL = intPreferencesKey("census_last_run_detail")
        val CENSUS_LAST_RUN_REASON = stringPreferencesKey("census_last_run_reason")
        val CENSUS_ACCEPTED_SCHEMA_IDS = stringSetPreferencesKey("census_policy_accepted_schema_ids")
        val CENSUS_POLICY = listOf("dailySkeletonBudget", "maxBatchItems", "maxBatchBytes", "maxSkeletonBytes", "k")
            .associateWith { intPreferencesKey("census_policy_$it") }
        val BUBBLE_SESSION_MODE = stringPreferencesKey("bubble_session_mode")
    }

    val addOnSpeech: Flow<String?> = ds.data.map { it[Keys.ADD_ON_SPEECH] }
    suspend fun setAddOnSpeech(value: String) { ds.edit { it[Keys.ADD_ON_SPEECH] = value } }

    val isDevModeUnlocked: Flow<Boolean?> = ds.data.map { it[Keys.IS_DEV_MODE_UNLOCKED] }
    val logLevel: Flow<Int?> = ds.data.map { it[Keys.LOG_LEVEL] }

    /**
     * The bubble's session-presentation experiment switch (#867), stored as the enum's wire string.
     * Raw here — the repository owns the fail-closed decode so the datastore stays type-free.
     */
    val bubbleSessionMode: Flow<String?> = ds.data.map { it[Keys.BUBBLE_SESSION_MODE] }

    suspend fun setDevModeUnlocked(unlocked: Boolean) {
        ds.edit { it[Keys.IS_DEV_MODE_UNLOCKED] = unlocked }
    }

    suspend fun setLogLevel(priority: Int) {
        ds.edit { it[Keys.LOG_LEVEL] = priority }
    }

    suspend fun setBubbleSessionMode(wire: String) {
        ds.edit { it[Keys.BUBBLE_SESSION_MODE] = wire }
    }

    val censusShareCaptures: Flow<Boolean> = ds.data.map { it[Keys.CENSUS_SHARE_CAPTURES] ?: false }
    val censusUploadEnabled: Flow<Boolean> = ds.data.map { it[Keys.CENSUS_UPLOAD_ENABLED] ?: false }
    val censusBaseUrl: Flow<String> = flowOf(DEFAULT_CENSUS_BASE_URL)
    val nextAllowedAtMillis: Flow<Long> = ds.data.map { it[Keys.CENSUS_NEXT_ALLOWED_AT] ?: 0L }
    val censusPolicy: Flow<Map<String, Int>> = ds.data.map { prefs ->
        Keys.CENSUS_POLICY.mapNotNull { (name, key) -> prefs[key]?.let { name to it } }.toMap()
    }

    /** Null distinguishes no cached policy from an explicitly empty accepted set. */
    val censusAcceptedSchemaIds: Flow<Set<String>?> = ds.data.map { it[Keys.CENSUS_ACCEPTED_SCHEMA_IDS] }

    data class RawCensusLastRun(
        val atMillis: Long, val outcomeWire: String?, val detail: Int?, val envelopesPosted: Int, val reasonWire: String?,
    )

    /** Raw fields of the last census run; the repository owns the fail-closed decode. */
    val censusLastRun: Flow<RawCensusLastRun?> = ds.data.map { prefs ->
        prefs[Keys.CENSUS_LAST_RUN_AT]?.let {
            RawCensusLastRun(it, prefs[Keys.CENSUS_LAST_RUN_OUTCOME], prefs[Keys.CENSUS_LAST_RUN_DETAIL],
                prefs[Keys.CENSUS_LAST_RUN_ENVELOPES] ?: 0, prefs[Keys.CENSUS_LAST_RUN_REASON])
        }
    }

    suspend fun setCensusLastRun(atMillis: Long, outcomeWire: String, detail: Int?, envelopesPosted: Int, reasonWire: String?) {
        ds.edit {
            it[Keys.CENSUS_LAST_RUN_ENVELOPES] = envelopesPosted
            it[Keys.CENSUS_LAST_RUN_AT] = atMillis
            it[Keys.CENSUS_LAST_RUN_OUTCOME] = outcomeWire
            if (detail != null) it[Keys.CENSUS_LAST_RUN_DETAIL] = detail else it.remove(Keys.CENSUS_LAST_RUN_DETAIL)
            if (reasonWire != null) it[Keys.CENSUS_LAST_RUN_REASON] = reasonWire else it.remove(Keys.CENSUS_LAST_RUN_REASON)
        }
    }

    /**
     * Identity reset, ONE edit: forget the server policy and write the reset as the last run. Consent is untouched, and
     * so is the deferral deadline — a server Retry-After / exhausted budget is server policy, not identity state.
     */
    suspend fun recordCensusReset(atMillis: Long, outcomeWire: String) {
        ds.edit { prefs ->
            Keys.CENSUS_POLICY.values.forEach { prefs.remove(it) }
            prefs.remove(Keys.CENSUS_ACCEPTED_SCHEMA_IDS)
            prefs[Keys.CENSUS_LAST_RUN_ENVELOPES] = 0
            prefs[Keys.CENSUS_LAST_RUN_AT] = atMillis
            prefs[Keys.CENSUS_LAST_RUN_OUTCOME] = outcomeWire
            prefs.remove(Keys.CENSUS_LAST_RUN_DETAIL)
            prefs.remove(Keys.CENSUS_LAST_RUN_REASON)
        }
    }

    suspend fun setCensusShareCaptures(enabled: Boolean) {
        ds.edit { it[Keys.CENSUS_SHARE_CAPTURES] = enabled }
    }

    /** #1621: the census switch and its receipt from ONE emission, so a reader never pairs one with the other's past. */
    data class RawCensusConsent(val switchOn: Boolean, val receipt: CensusConsentReceipt?)

    /** A malformed receipt reads as absent, which reads as consent OFF. */
    val censusConsent: Flow<RawCensusConsent> = ds.data.map { prefs ->
        RawCensusConsent(
            switchOn = prefs[Keys.CENSUS_UPLOAD_ENABLED] ?: false,
            receipt = prefs[Keys.CENSUS_CONSENT_RECEIPT]?.let { encoded ->
                runCatching { ConsentReceiptJson.decodeFromString<CensusConsentReceipt>(encoded) }.getOrNull()
            },
        )
    }

    /** #1625: moves on every write of the census switch (on, off, a decline, a server revoke). */
    val censusConsentSeq: Flow<Long> = ds.data.map { it[Keys.CENSUS_CONSENT_SEQ] ?: 0L }

    val censusWifiOnly: Flow<Boolean> = ds.data.map { it[Keys.CENSUS_WIFI_ONLY] ?: CensusChoices.DEFAULT_WIFI_ONLY }

    /** Platform wire values; null until a choice is saved. */
    val censusPlatforms: Flow<Set<String>?> = ds.data.map { it[Keys.CENSUS_PLATFORMS] }

    /**
     * The census switch, in ONE edit with what goes with it: a [receipt] when the dasher decided (null keeps the stored
     * one, as a server revoke does), the choices saved with an Accept, sharing off when the switch goes off, and the
     * deferral deadline cleared.
     */
    suspend fun setCensusUploadEnabled(
        enabled: Boolean,
        receipt: CensusConsentReceipt? = null,
        platforms: Set<String>? = null,
        wifiOnly: Boolean? = null,
    ) {
        ds.edit {
            // #1625: every consent write moves the sequence in the same edit, so no change (nor an off then on) is missed.
            it[Keys.CENSUS_CONSENT_SEQ] = (it[Keys.CENSUS_CONSENT_SEQ] ?: 0L) + 1
            it[Keys.CENSUS_UPLOAD_ENABLED] = enabled
            // One transaction: cancellation or process death cannot leave sharing ON with consent OFF.
            if (!enabled) it[Keys.CENSUS_SHARE_CAPTURES] = false
            it.remove(Keys.CENSUS_NEXT_ALLOWED_AT)
            if (receipt != null) it[Keys.CENSUS_CONSENT_RECEIPT] = ConsentReceiptJson.encodeToString(receipt)
            if (platforms != null) it[Keys.CENSUS_PLATFORMS] = platforms
            if (wifiOnly != null) it[Keys.CENSUS_WIFI_ONLY] = wifiOnly
        }
    }

    suspend fun setCensusWifiOnly(wifiOnly: Boolean) {
        ds.edit { it[Keys.CENSUS_WIFI_ONLY] = wifiOnly }
    }

    /**
     * Adds or removes one platform inside the edit, so two quick changes each apply to the other's result. Before any
     * choice is saved the set starts from [unsavedDefault].
     */
    suspend fun setCensusPlatform(wire: String, covered: Boolean, unsavedDefault: Set<String>) {
        ds.edit {
            val current = it[Keys.CENSUS_PLATFORMS] ?: unsavedDefault
            it[Keys.CENSUS_PLATFORMS] = if (covered) current + wire else current - wire
        }
    }

    suspend fun setNextAllowedAtMillis(value: Long) {
        ds.edit { it[Keys.CENSUS_NEXT_ALLOWED_AT] = value }
    }

    /** #1620: the drawn time of the next daily census run; null until the first draw. */
    val censusNextRunAtMillis: Flow<Long?> = ds.data.map { it[Keys.CENSUS_NEXT_RUN_AT] }

    suspend fun setCensusNextRunAtMillis(value: Long) {
        ds.edit { it[Keys.CENSUS_NEXT_RUN_AT] = value }
    }

    suspend fun clearCensusNextRunAtMillis() {
        ds.edit { it.remove(Keys.CENSUS_NEXT_RUN_AT) }
    }

    data class RawCensusDeletion(
        val requestedAtMillis: Long, val stateWire: String?, val localCleared: Boolean,
        val installId: String? = null, val rows: Int? = null, val deletedDay: String? = null, val waitingWire: String? = null,
    )

    /** #1622: the last census deletion request, raw; the repository owns the fail-closed decode. */
    val censusDeletion: Flow<RawCensusDeletion?> = ds.data.map { prefs ->
        prefs[Keys.CENSUS_DELETION_AT]?.let {
            RawCensusDeletion(it, prefs[Keys.CENSUS_DELETION_STATE], prefs[Keys.CENSUS_DELETION_LOCAL_CLEARED] ?: false,
                prefs[Keys.CENSUS_DELETION_INSTALL_ID], prefs[Keys.CENSUS_DELETION_ROWS], prefs[Keys.CENSUS_DELETION_DAY],
                prefs[Keys.CENSUS_DELETION_WAITING])
        }
    }

    /** #1622: forgets the deletion record (a "nothing sent" result once the phone enrols again). */
    suspend fun clearCensusDeletion() {
        ds.edit { prefs ->
            listOf(Keys.CENSUS_DELETION_AT, Keys.CENSUS_DELETION_STATE, Keys.CENSUS_DELETION_LOCAL_CLEARED,
                Keys.CENSUS_DELETION_INSTALL_ID, Keys.CENSUS_DELETION_ROWS, Keys.CENSUS_DELETION_DAY, Keys.CENSUS_DELETION_WAITING,
            ).forEach { prefs.remove(it) }
        }
    }

    /** #1622: the whole deletion record in ONE edit, so a reader never sees a state with another request's figures. */
    suspend fun setCensusDeletion(value: RawCensusDeletion) {
        ds.edit {
            it[Keys.CENSUS_DELETION_AT] = value.requestedAtMillis
            if (value.stateWire != null) it[Keys.CENSUS_DELETION_STATE] = value.stateWire else it.remove(Keys.CENSUS_DELETION_STATE)
            it[Keys.CENSUS_DELETION_LOCAL_CLEARED] = value.localCleared
            if (value.installId != null) it[Keys.CENSUS_DELETION_INSTALL_ID] = value.installId else it.remove(Keys.CENSUS_DELETION_INSTALL_ID)
            if (value.rows != null) it[Keys.CENSUS_DELETION_ROWS] = value.rows else it.remove(Keys.CENSUS_DELETION_ROWS)
            if (value.deletedDay != null) it[Keys.CENSUS_DELETION_DAY] = value.deletedDay else it.remove(Keys.CENSUS_DELETION_DAY)
            if (value.waitingWire != null) it[Keys.CENSUS_DELETION_WAITING] = value.waitingWire else it.remove(Keys.CENSUS_DELETION_WAITING)
        }
    }

    suspend fun setCensusPolicy(value: Map<String, Int>, acceptedSchemaIds: Set<String>) {
        ds.edit { prefs ->
            prefs.remove(stringPreferencesKey("census_policy"))
            prefs[Keys.CENSUS_ACCEPTED_SCHEMA_IDS] = acceptedSchemaIds
            Keys.CENSUS_POLICY.forEach { (name, key) ->
                val limit = value[name]
                if (limit != null) prefs[key] = limit else prefs.remove(key)
            }
        }
    }

    private companion object {
        const val DEFAULT_CENSUS_BASE_URL = "https://census.dashbuddy.trotter.cloud"
    }

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}