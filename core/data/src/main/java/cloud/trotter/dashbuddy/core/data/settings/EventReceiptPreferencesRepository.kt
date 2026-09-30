package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE owner of the wide-event-receipt consent (#1151). Eagerly materialized for the app's lifetime
 * (the [PlatformPreferencesRepository] pattern, #356): the accessibility listener, the Dashboard
 * prompt and the Settings switch all read this one [StateFlow].
 *
 * **Fail-closed decode:** nothing saved, or an unrecognized stored name, reads as
 * [EventReceiptConsent.UNDECIDED] — the filtered footprint. Only an explicit ALLOWED widens. The
 * pre-read / unreadable value is `null`, which every consumer treats as UNDECIDED.
 */
@Singleton
class EventReceiptPreferencesRepository @Inject constructor(
    private val dataSource: EventReceiptConsentDataSource,
    @ApplicationScope scope: CoroutineScope,
) : EventReceiptPreferences {

    /**
     * THE materialization: `null` until the first read. **Fails closed, loud, and recovers (review
     * LL7/MM5):** a failed read emits `null` (filtered footprint, no prompt) and is RETRIED with
     * bounded backoff ([RETRY_BASE_MS] × attempt, at most [MAX_RETRIES] per failure episode), so a
     * transient I/O error cannot freeze the value for the process lifetime. One ERROR per episode; a
     * successful read ends the episode. Only when the retries are exhausted does the flow settle on
     * `null` for good.
     */
    override val consent: StateFlow<EventReceiptConsent?> = consentFlow()
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun consentFlow(): Flow<EventReceiptConsent?> {
        var failuresInEpisode = 0
        return dataSource.consent
            .map<String?, EventReceiptConsent?> { decode(it) }
            .onEach { failuresInEpisode = 0 }
            .retryWhen { cause, _ ->
                if (failuresInEpisode == 0) {
                    Timber.tag("Data").e(cause, "event-receipt consent unreadable — treating as undecided")
                }
                failuresInEpisode++
                emit(null)
                if (failuresInEpisode > MAX_RETRIES) {
                    false
                } else {
                    delay(RETRY_BASE_MS * failuresInEpisode)
                    true
                }
            }
            .catch { emit(null) } // retries exhausted: stay fail-closed (already logged)
    }

    override suspend fun set(consent: EventReceiptConsent) {
        dataSource.setConsent(consent.name)
    }

    companion object {
        internal const val MAX_RETRIES = 5
        internal const val RETRY_BASE_MS = 1_000L

        /** Stored name → decision; absent or unknown ⇒ [EventReceiptConsent.UNDECIDED]. */
        internal fun decode(name: String?): EventReceiptConsent =
            EventReceiptConsent.entries.firstOrNull { it.name == name } ?: EventReceiptConsent.UNDECIDED
    }
}
