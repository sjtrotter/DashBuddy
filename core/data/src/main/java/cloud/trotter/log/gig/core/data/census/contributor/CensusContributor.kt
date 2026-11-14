package cloud.trotter.log.gig.core.data.census.contributor

import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.census.ContributorDataSource
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.GrantResult
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.census.contributor.ContributorEnd
import cloud.trotter.log.gig.domain.census.contributor.ContributorEntry
import cloud.trotter.log.gig.domain.census.contributor.ContributorGate
import cloud.trotter.log.gig.domain.census.contributor.ContributorGrant
import cloud.trotter.log.gig.domain.census.contributor.ContributorInvitation
import cloud.trotter.log.gig.domain.census.contributor.ContributorRecord
import cloud.trotter.log.gig.domain.census.contributor.GrantRefusal
import cloud.trotter.log.gig.domain.census.contributor.InvitationAnswer
import cloud.trotter.log.gig.domain.census.contributor.PendingContributorReviews
import cloud.trotter.log.gig.domain.census.contributor.ShiftActivity
import cloud.trotter.log.gig.domain.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** #1625: what the Census screen's "Screen reviews" row and the reviewer screen show. Holds no eligibility count. */
data class ContributorStatus(
    val record: ContributorRecord,
    val entry: ContributorEntry,
    /** Eligible, no shift running and no live grant: the reviewer screen may offer Accept. */
    val canAccept: Boolean,
    val shiftLive: Boolean,
)

/** #1625: how one Accept went. Each says what happened; the 403 answers are also stored on the record. */
enum class GrantRequestOutcome {
    GRANTED,
    INSTALL_TOO_NEW,
    NOT_ELIGIBLE,
    GRANT_REVOKED,
    /** Offline, the server busy or unreachable, or it could not confirm this phone's census ID. */
    UNREACHABLE,
    /** A census deletion request is still waiting; nothing else is sent until it resolves. */
    DELETION_PENDING,
    /** The phone has no enrolled census ID yet. */
    NO_CENSUS_ID,
    /** A shift is running, or the phone is not eligible now: nothing was sent. */
    NOT_OPEN,
}

/**
 * #1625: the contributor tier on the phone (ADR-0011 §15): the invitation, the grant and the check before any
 * contributor upload ([ContributorGate], used by #1626).
 *
 * The invitation follows [ContributorInvitation]: offered after a shift ends ([inviteAfterShift]), never during one,
 * at most once until answered. Accept ([acceptAsync]) runs under the census upload lock: a waiting deletion request
 * goes first and blocks it, then the phone sends `{"eligible":true}` and nothing else, and stores the grant with its
 * install ID and expiry. A grant ends on expiry, revocation, the dasher's Stop, the census turned off, a new census ID
 * or a deletion request; ending it stops contributor uploads and clears the reviews waiting on the phone
 * ([PendingContributorReviews]). An ended grant renews only by asking again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CensusContributor internal constructor(
    private val store: ContributorDataSource,
    private val eligibility: suspend (nowMillis: Long, zone: ZoneId) -> Boolean,
    private val shifts: ShiftActivity,
    private val settings: DevSettingsRepository,
    private val credentials: CensusCredentialStore,
    private val deletion: CensusDataDeletion,
    private val lock: CensusUploadLock,
    private val apis: CensusApiFactory,
    private val pendingReviews: PendingContributorReviews,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val clock: Clock,
    private val zone: () -> ZoneId,
) : ContributorGate {
    @Inject constructor(
        store: ContributorDataSource,
        reader: ContributorEligibilityReader,
        shifts: ShiftActivity,
        settings: DevSettingsRepository,
        credentials: CensusCredentialStore,
        deletion: CensusDataDeletion,
        lock: CensusUploadLock,
        apis: CensusApiFactory,
        pendingReviews: PendingContributorReviews,
        @ApplicationScope scope: CoroutineScope,
        @Named("appVersionName") appVersion: String,
        clock: Clock,
    ) : this(store, reader::eligible, shifts, settings, credentials, deletion, lock, apis, pendingReviews, scope,
        appVersion, clock, ZoneId::systemDefault)

    private val requestingFlow = MutableStateFlow(false)
    private val outcomeFlow = MutableStateFlow<GrantRequestOutcome?>(null)

    /** True while a grant request from this process is being made. */
    val requesting: StateFlow<Boolean> = requestingFlow.asStateFlow()

    /** The last Accept's outcome in this process, for the reviewer screen; null before one. */
    val lastOutcome: StateFlow<GrantRequestOutcome?> = outcomeFlow.asStateFlow()

    val status: Flow<ContributorStatus> =
        combine(store.record, settings.censusConsent, shifts.shiftLive) { record, consent, live -> Triple(record, consent, live) }
            .mapLatest { (record, consent, live) ->
                val now = clock.millis()
                val today = todayUtc()
                val censusOn = consent == CensusConsent.ON
                val eligible = censusOn && eligibility(now, zone())
                ContributorStatus(
                    record = record,
                    entry = ContributorInvitation.entry(record, censusOn, eligible, live, now, today),
                    canAccept = eligible && !live && record.grant?.liveOn(today) != true,
                    shiftLive = live,
                )
            }

    /**
     * Called by the app when the last running shift has ended. Returns the record when the invitation notification
     * should post now, marked as offered so it posts at most once until the dasher answers; null otherwise. Never
     * throws.
     */
    suspend fun inviteAfterShift(): ContributorRecord? = guarded(null) {
        if (shifts.shiftLive.first()) return@guarded null
        reconcile()
        val now = clock.millis()
        val today = todayUtc()
        val eligible = eligibility(now, zone())
        var offered: ContributorRecord? = null
        store.update { record ->
            if (ContributorInvitation.shouldNotify(record, eligible, shiftLive = false, now, today)) {
                record.copy(invitedAtMillis = now).also { offered = it }
            } else record
        }
        if (offered != null) Timber.tag(TAG).i("census contributor invited")
        offered
    }

    /** Accept on the reviewer screen. Runs on the application scope, so leaving the screen cannot cut it short. */
    fun acceptAsync(): Job = scope.launch { accept() }

    suspend fun accept(): GrantRequestOutcome {
        requestingFlow.value = true
        try {
            val outcome = lock.mutex.withLock { requestHoldingLock() }
            outcomeFlow.value = outcome
            Timber.tag(TAG).i("census contributor request outcome=%s", outcome.name.lowercase())
            return outcome
        } finally {
            requestingFlow.value = false
        }
    }

    private suspend fun requestHoldingLock(): GrantRequestOutcome {
        val now = clock.millis()
        if (shifts.shiftLive.first() || !eligibility(now, zone())) return GrantRequestOutcome.NOT_OPEN
        // A waiting deletion request goes before anything else, and while it waits nothing else is sent.
        val clear = try {
            deletion.resumePending()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!clear) return GrantRequestOutcome.DELETION_PENDING
        val credential = try {
            credentials.current()
        } catch (_: CensusCredentialStore.Unusable) {
            null
        } catch (_: CensusCredentialStore.Transient) {
            return GrantRequestOutcome.UNREACHABLE
        } ?: return GrantRequestOutcome.NO_CENSUS_ID
        // The decision is recorded before the request, whatever the server answers.
        store.update { it.copy(receipt = receipt(now, granted = true)) }
        val result = try {
            apis.create(settings.censusBaseUrl.first()).requestContributorGrant(credential.bearer())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            GrantResult.TransportFailure("Exception")
        }
        return when (result) {
            is GrantResult.Granted -> {
                val grant = ContributorGrant(credential.installId, result.grantedDay, result.expiresDay, now)
                store.update { it.granted(grant, now) }
                GrantRequestOutcome.GRANTED
            }
            GrantResult.InstallTooNew -> refuse(GrantRefusal.INSTALL_TOO_NEW, now, GrantRequestOutcome.INSTALL_TOO_NEW)
            GrantResult.NotEligible -> refuse(GrantRefusal.NOT_ELIGIBLE, now, GrantRequestOutcome.NOT_ELIGIBLE)
            GrantResult.GrantRevoked -> refuse(GrantRefusal.GRANT_REVOKED, now, GrantRequestOutcome.GRANT_REVOKED)
            // The POST never answers these; a state read does. Either way nothing was granted.
            GrantResult.Expired, GrantResult.None, GrantResult.InstallRevoked, is GrantResult.Unauthorized,
            is GrantResult.RateLimited, is GrantResult.ServerUnavailable, is GrantResult.TransportFailure ->
                GrantRequestOutcome.UNREACHABLE
        }
    }

    private suspend fun refuse(refusal: GrantRefusal, now: Long, outcome: GrantRequestOutcome): GrantRequestOutcome {
        store.update { it.refused(refusal, now) }
        if (refusal == GrantRefusal.GRANT_REVOKED) clearPendingReviews()
        return outcome
    }

    /** The screen's answers run on the application scope: the screen closes on Not now and No thanks. */
    fun notNowAsync(): Job = scope.launch { notNow() }
    fun noThanksAsync(): Job = scope.launch { noThanks() }
    fun stopAsync(): Job = scope.launch { stop() }

    /** Not now: no repeat for [ContributorInvitation.NOT_NOW_DAYS] days. */
    suspend fun notNow() {
        store.update { it.answered(InvitationAnswer.NOT_NOW, clock.millis()) }
    }

    /** No thanks: no invitation again until the dasher accepts from Settings. Recorded as a declined receipt. */
    suspend fun noThanks() {
        val now = clock.millis()
        store.update { it.answered(InvitationAnswer.NO_THANKS, now, receipt(now, granted = false)) }
    }

    /** Stop reviewing: the grant ends on the phone now, recorded as a declined receipt and a No thanks. */
    suspend fun stop() {
        val now = clock.millis()
        store.update { it.ended(ContributorEnd.STOPPED, now, receipt(now, granted = false)) }
        clearPendingReviews()
        Timber.tag(TAG).i("census contributor ended reason=stopped")
    }

    /**
     * [ContributorGate]: true only while a grant stands. A grant that no longer stands is ended here. Takes no lock, so
     * an upload that holds the census upload lock can call it.
     */
    override suspend fun mayUpload(): Boolean = guarded(false) { reconcile() }

    /**
     * Reads the grant's state on the server (`GET /v1/contributor/grant`), as the server's client guide asks before
     * each review session, and ends the phone's grant when the server says it expired, was revoked or is gone. A
     * granted answer only updates the days of the grant the phone already holds: it never creates one, so a grant the
     * dasher stopped stays stopped. Returns [mayUpload] afterwards. Takes no lock.
     */
    suspend fun refresh(): Boolean = guarded(false) {
        val grant = store.record.first().grant ?: return@guarded false
        if (!reconcile()) return@guarded false
        val credential = credentials.current()?.takeIf { it.installId == grant.installId } ?: return@guarded reconcile()
        when (val result = apis.create(settings.censusBaseUrl.first()).contributorGrant(credential.bearer())) {
            is GrantResult.Granted -> store.update { record ->
                if (record.grant == grant) record.copy(grant = grant.copy(grantedDay = result.grantedDay, expiresDay = result.expiresDay))
                else record
            }
            GrantResult.Expired -> end(grant, ContributorEnd.EXPIRED)
            GrantResult.GrantRevoked -> end(grant, ContributorEnd.REVOKED)
            GrantResult.None -> end(grant, ContributorEnd.SERVER_NONE)
            else -> Unit
        }
        reconcile()
    }

    /** App start: ends a grant whenever what it stands on changes (consent, census ID, a deletion request). */
    fun watch(): Job = scope.launch {
        combine(store.record, settings.censusConsent, credentials.installId, settings.censusDeletion) { record, _, _, _ ->
            record.grant
        }.catch { e ->
            // mayUpload checks again before any upload, so a watch that stops leaves nothing open.
            Timber.tag(TAG).w("census contributor watch stopped cause=%s", e.javaClass.simpleName)
        }.collect { grant -> if (grant != null) guarded(false) { reconcile() } }
    }

    /** True when a live grant stands after the check; a grant that no longer stands is ended with its reason. */
    private suspend fun reconcile(): Boolean {
        val grant = store.record.first().grant ?: return false
        val reason = when {
            settings.censusConsent.first() != CensusConsent.ON -> ContributorEnd.CENSUS_OFF
            credentials.installId.first() != grant.installId -> ContributorEnd.ID_CHANGED
            settings.censusDeletion.first()?.let { it.requestedAtMillis >= grant.obtainedAtMillis } == true ->
                ContributorEnd.DATA_DELETED
            !grant.liveOn(todayUtc()) -> ContributorEnd.EXPIRED
            else -> return true
        }
        end(grant, reason)
        return false
    }

    /** Ends [grant] (only that one: a newer grant stays) and clears the reviews waiting on the phone. */
    private suspend fun end(grant: ContributorGrant, reason: ContributorEnd) {
        val now = clock.millis()
        store.update { record -> if (record.grant == grant) record.ended(reason, now) else record }
        clearPendingReviews()
        Timber.tag(TAG).i("census contributor ended reason=%s", reason.name.lowercase())
    }

    private suspend fun clearPendingReviews() {
        try {
            pendingReviews.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Timber.tag(TAG).w("census contributor pending reviews clear failed")
        }
    }

    private fun receipt(now: Long, granted: Boolean) = ConsentReceipt(now, appVersion, PrivacyDisclosure.REVISION, granted)

    private fun todayUtc(): LocalDate = Instant.ofEpochMilli(clock.millis()).atZone(ZoneOffset.UTC).toLocalDate()

    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag(TAG).w("census contributor check failed cause=%s", e.javaClass.simpleName)
        fallback
    }

    private companion object {
        const val TAG = "Census"
    }
}
