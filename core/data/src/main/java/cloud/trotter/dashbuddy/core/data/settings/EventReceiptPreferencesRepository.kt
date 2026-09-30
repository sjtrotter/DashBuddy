package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE owner of the wide-event-receipt consent (#1151). Materialized once for the app's lifetime
 * (the [PlatformPreferencesRepository] pattern, #356): the accessibility listener, the Dashboard
 * prompt and the Settings switch all read this one [StateFlow].
 *
 * **Fail-closed decode:** nothing saved, or an unrecognized stored name, reads as
 * [EventReceiptConsent.UNDECIDED] — the filtered footprint. Only an explicit ALLOWED widens.
 *
 * **Shape (review PP1 — replaces the round-4 mutex/join/generation):** ONE collector on the
 * application scope reads the store into [consent]. `null` means ONLY "not read yet". A failed read
 * is retried with bounded backoff ([RETRY_BASE_MS] × attempt, at most [MAX_RETRIES] per failure
 * episode; a WARN per attempt, PP6); when the retries are exhausted it logs one ERROR and settles on
 * the value already known this process, else [EventReceiptConsent.UNDECIDED] — filtered but USABLE.
 * [set] publishes optimistically, then writes on the APPLICATION scope (a closing screen can never
 * cancel it half-way), so a read that fails after a write can only re-emit the written value.
 */
@Singleton
class EventReceiptPreferencesRepository @Inject constructor(
    private val dataSource: EventReceiptConsentDataSource,
    @param:ApplicationScope private val scope: CoroutineScope,
) : EventReceiptPreferences {

    private val _consent = MutableStateFlow<EventReceiptConsent?>(null)
    override val consent: StateFlow<EventReceiptConsent?> = _consent.asStateFlow()

    init {
        scope.launch {
            var failuresInEpisode = 0
            dataSource.consent
                .map { decode(it) }
                .onEach { failuresInEpisode = 0 }
                .retryWhen { cause, _ ->
                    failuresInEpisode++
                    if (failuresInEpisode > MAX_RETRIES) {
                        false
                    } else {
                        Timber.tag("Data").w(
                            cause,
                            "event-receipt consent unreadable — retrying (%d/%d)",
                            failuresInEpisode,
                            MAX_RETRIES,
                        )
                        delay(RETRY_BASE_MS * failuresInEpisode)
                        true
                    }
                }
                .catch { t ->
                    Timber.tag("Data").e(t, "event-receipt consent unreadable — retries exhausted")
                    emit(_consent.value ?: EventReceiptConsent.UNDECIDED)
                }
                .collect { _consent.value = it }
        }
    }

    /**
     * Persist a decision (review NN3/PP1): published optimistically, then written on the APPLICATION
     * scope. Never throws a storage failure — it is logged (ERROR, tag `Data`), the optimistic value
     * is rolled back (if nothing newer replaced it) and `false` is returned.
     */
    override suspend fun set(consent: EventReceiptConsent): Boolean {
        val previous = _consent.value
        _consent.value = consent
        return scope.async {
            try {
                dataSource.setConsent(consent.name)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("Data").e(e, "event-receipt consent write failed — decision not saved")
                _consent.compareAndSet(consent, previous)
                false
            }
        }.await()
    }

    companion object {
        internal const val MAX_RETRIES = 5
        internal const val RETRY_BASE_MS = 1_000L

        /** Stored name → decision; absent or unknown ⇒ [EventReceiptConsent.UNDECIDED]. */
        internal fun decode(name: String?): EventReceiptConsent =
            EventReceiptConsent.entries.firstOrNull { it.name == name } ?: EventReceiptConsent.UNDECIDED
    }
}
