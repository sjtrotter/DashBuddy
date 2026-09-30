package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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
     * THE materialization: `null` until the first read. **Fails closed and LOUD:** a store that
     * cannot be read (corrupt file, I/O) emits `null` — filtered footprint, no prompt — and logs an
     * ERROR, instead of terminating the sharing coroutine and freezing every reader.
     */
    override val consent: StateFlow<EventReceiptConsent?> = dataSource.consent
        .map<String?, EventReceiptConsent?> { decode(it) }
        .catch { t ->
            Timber.tag("Data").e(t, "event-receipt consent unreadable — treating as undecided")
            emit(null)
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun set(consent: EventReceiptConsent) {
        dataSource.setConsent(consent.name)
    }

    companion object {
        /** Stored name → decision; absent or unknown ⇒ [EventReceiptConsent.UNDECIDED]. */
        internal fun decode(name: String?): EventReceiptConsent =
            EventReceiptConsent.entries.firstOrNull { it.name == name } ?: EventReceiptConsent.UNDECIDED
    }
}
