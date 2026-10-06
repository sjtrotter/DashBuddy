package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import cloud.trotter.dashbuddy.domain.capability.PrivacyDisclosure
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

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
 *
 * **The collector is the ONLY writer of [consent] (review SS1).** [set] only writes the store, on the
 * APPLICATION scope (a closing screen can never cancel it half-way); DataStore's data flow emits
 * after the edit, so a decision reaches every reader within milliseconds through the collector. No
 * optimistic value and no rollback: a value-compared rollback let overlapping or failing writes leave
 * [consent] disagreeing with the store (or restore `null` after a read). There is exactly ONE
 * collector for the repository's lifetime (review UU1/UU2); after its retries are exhausted it waits
 * for a conflated "read again" poke, which every successful write sends — so the decision is always
 * read back, including a write that lands while the collector is still in its exhausted `catch`.
 * Between the final failure and `receive()` the collector has no suspension point (the catch's
 * emit only sets a StateFlow), so "during the catch" and "before the catch completes" are the same
 * window for the conflated poke — a buffered poke is consumed at `receive()` either way (review VV3).
 */
@Singleton
class EventReceiptPreferencesRepository @Inject constructor(
    private val dataSource: EventReceiptConsentDataSource,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:Named("appVersionName") private val appVersion: String,
) : EventReceiptPreferences {

    private val _consent = MutableStateFlow<EventReceiptConsent?>(null)
    override val consent: StateFlow<EventReceiptConsent?> = _consent.asStateFlow()

    private val _receipt = MutableStateFlow<ConsentReceipt?>(null)
    override val receipt: StateFlow<ConsentReceipt?> = _receipt.asStateFlow()

    /**
     * #1151 review UU1/UU2 — "read again" pokes. CONFLATED: a poke sent while the collector is still
     * inside its exhausted `catch` is buffered and consumed the moment it reaches `receive()`, so a
     * write that lands during that window triggers exactly one re-read; a poke sent while the
     * collector is healthy is consumed only after a future exhaustion (one harmless extra
     * re-subscribe).
     */
    private val reread = Channel<Unit>(Channel.CONFLATED)

    init {
        // ONE collector for the repository's lifetime, by construction — it never gives up
        // permanently: after an exhausted read it waits for a poke from a successful write.
        scope.launch {
            while (isActive) {
                readOnce()
                reread.receive()
            }
        }
    }

    /** Reads the store into [consent]; returns only when the retries were exhausted. */
    private suspend fun readOnce() {
        var failuresInEpisode = 0
        dataSource.snapshot
            .map { decode(it.name) to it.receipt }
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
                emit((_consent.value ?: EventReceiptConsent.UNDECIDED) to _receipt.value)
            }
            .collect { (decision, record) ->
                _consent.value = decision
                _receipt.value = record
            }
    }

    /**
     * Persist a decision (review NN3/SS1): written on the APPLICATION scope; the collector publishes
     * it. Never throws a storage failure — it is logged (ERROR, tag `Data`) and `false` is returned,
     * [consent] untouched.
     */
    override suspend fun set(consent: EventReceiptConsent): Boolean =
        scope.async {
            try {
                dataSource.setConsent(
                    consent.name,
                    ConsentReceipt(
                        System.currentTimeMillis(), appVersion, PrivacyDisclosure.REVISION,
                        granted = consent == EventReceiptConsent.ALLOWED,
                    ),
                )
                reread.trySend(Unit) // UU1: a collector that gave up re-reads (conflated poke)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("Data").e(e, "event-receipt consent write failed — decision not saved")
                false
            }
        }.await()

    companion object {
        internal const val MAX_RETRIES = 5
        internal const val RETRY_BASE_MS = 1_000L

        /** Stored name → decision; absent or unknown ⇒ [EventReceiptConsent.UNDECIDED]. */
        internal fun decode(name: String?): EventReceiptConsent =
            EventReceiptConsent.entries.firstOrNull { it.name == name } ?: EventReceiptConsent.UNDECIDED
    }
}
