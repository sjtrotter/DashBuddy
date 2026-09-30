package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE owner of the wide-event-receipt consent (#1151). Eagerly materialized for the app's lifetime
 * (the [PlatformPreferencesRepository] pattern, #356): the accessibility listener, the Dashboard
 * prompt and the Settings switch all read this one [StateFlow].
 *
 * **Fail-closed decode:** nothing saved, or an unrecognized stored name, reads as
 * [EventReceiptConsent.UNDECIDED] — the filtered footprint. Only an explicit ALLOWED widens.
 */
@Singleton
class EventReceiptPreferencesRepository @Inject constructor(
    private val dataSource: EventReceiptConsentDataSource,
    @ApplicationScope scope: CoroutineScope,
) : EventReceiptPreferences {

    /** null until the store has been read — the pre-load marker behind [loaded]. */
    private val stored: StateFlow<EventReceiptConsent?> = dataSource.consent
        .map { decode(it) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val consent: StateFlow<EventReceiptConsent> = stored
        .map { it ?: EventReceiptConsent.UNDECIDED }
        .stateIn(scope, SharingStarted.Eagerly, EventReceiptConsent.UNDECIDED)

    override val loaded: StateFlow<Boolean> = stored
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override suspend fun set(consent: EventReceiptConsent) {
        dataSource.setConsent(consent.name)
    }

    companion object {
        /** Stored name → decision; absent or unknown ⇒ [EventReceiptConsent.UNDECIDED]. */
        internal fun decode(name: String?): EventReceiptConsent =
            EventReceiptConsent.entries.firstOrNull { it.name == name } ?: EventReceiptConsent.UNDECIDED
    }
}
