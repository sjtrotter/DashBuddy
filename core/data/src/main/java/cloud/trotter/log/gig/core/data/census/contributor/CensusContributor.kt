package cloud.trotter.log.gig.core.data.census.contributor

import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.census.CensusInstallRevocation
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.census.ContributorDataSource
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.GrantResult
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.census.CensusLastRun
import cloud.trotter.log.gig.domain.census.CensusRunOutcome
import cloud.trotter.log.gig.domain.census.contributor.ContributorEnd
import cloud.trotter.log.gig.domain.census.contributor.ContributorEntry
import cloud.trotter.log.gig.domain.census.contributor.ContributorGate
import cloud.trotter.log.gig.domain.census.contributor.ContributorGrant
import cloud.trotter.log.gig.domain.census.contributor.ContributorInvitation
import cloud.trotter.log.gig.domain.census.contributor.ContributorRecord
import cloud.trotter.log.gig.domain.census.contributor.DecisionVersion
import cloud.trotter.log.gig.domain.census.contributor.GrantRefusal
import cloud.trotter.log.gig.domain.census.contributor.InvitationAnswer
import cloud.trotter.log.gig.domain.census.contributor.PendingContributorReviews
import cloud.trotter.log.gig.domain.census.contributor.ShiftActivity
import cloud.trotter.log.gig.domain.di.ApplicationScope
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** #1625: what the Census screen's "Screen reviews" row and the reviewer screen show. Holds no eligibility count. */
data class ContributorStatus(
    val record: ContributorRecord,
    val entry: ContributorEntry,
    /** Eligible, no shift running and no live grant: the reviewer screen may offer Accept. */
    val canAccept: Boolean,
    val shiftLive: Boolean,
    /** What an Accept pressed now is decided under; the screen passes it to [CensusContributor.acceptAsync]. */
    val version: DecisionVersion,
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
    /** The dasher declined, stopped, or the census changed while the request waited: its answer was not used. */
    SUPERSEDED,
    /** `401 revoked`: the census ID itself is revoked; the census is off and the ID forgotten. */
    INSTALL_REVOKED,
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
    private val revokeInstall: suspend () -> Unit,
    /** Re-reads the time-dependent status (eligibility, a grant's expiry) without any other state change. */
    private val ticks: Flow<Unit>,
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
        revocation: CensusInstallRevocation,
    ) : this(store, reader::eligible, shifts, settings, credentials, deletion, lock, apis, pendingReviews, scope,
        appVersion, clock, ZoneId::systemDefault, revocation::apply, minuteTicks())

    private val requestingFlow = MutableStateFlow(false)
    private val outcomeFlow = MutableStateFlow<GrantRequestOutcome?>(null)

    /** True while a grant request from this process is being made. */
    val requesting: StateFlow<Boolean> = requestingFlow.asStateFlow()

    /** The last Accept's outcome in this process, for the reviewer screen; null before one. */
    val lastOutcome: StateFlow<GrantRequestOutcome?> = outcomeFlow.asStateFlow()

    private val versions: Flow<Pair<ContributorRecord, DecisionVersion>> = combine(
        store.record, settings.censusConsentSeq, settings.censusBaseUrl, credentials.installId, settings.censusDeletion,
    ) { record, consent, endpoint, installId, deletion ->
        record to DecisionVersion(record.generation, consent, endpoint, installId, deletion?.requestedAtMillis)
    }

    /** The version as stored now. */
    suspend fun decisionVersion(): DecisionVersion = versions.first().second

    val status: Flow<ContributorStatus> =
        combine(versions, settings.censusConsent, shifts.shiftLive, ticks) { (record, version), consent, live, _ ->
            Triple(record to version, consent, live)
        }
            .mapLatest { (recordVersion, consent, live) ->
                val (stored, version) = recordVersion
                // A grant that no longer stands (expired, or decided under another version) is not shown as one.
                val record = if (stored.grant?.standsOn(todayUtc(), version) == false) stored.copy(grant = null) else stored
                val now = clock.millis()
                val today = todayUtc()
                val censusOn = consent == CensusConsent.ON
                val eligible = censusOn && eligibility(now, zone())
                ContributorStatus(
                    record = record,
                    entry = ContributorInvitation.entry(record, censusOn, eligible, live, now, today),
                    canAccept = eligible && !live && record.grant?.liveOn(today) != true,
                    shiftLive = live,
                    version = version,
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

    /**
     * Accept on the reviewer screen, decided under [version] (the screen's, as shown when pressed). Runs on the
     * application scope, so leaving the screen cannot cut it short.
     */
    fun acceptAsync(version: DecisionVersion): Job = scope.launch { accept(version) }

    /** [version] is what the press was decided under; null reads it now, before any wait. */
    suspend fun accept(version: DecisionVersion? = null): GrantRequestOutcome {
        requestingFlow.value = true
        try {
            val pressed = version ?: decisionVersion()
            val outcome = lock.mutex.withLock { requestHoldingLock(pressed) }
            outcomeFlow.value = outcome
            Timber.tag(TAG).i("census contributor request outcome=%s", outcome.name.lowercase())
            return outcome
        } finally {
            requestingFlow.value = false
        }
    }

    private suspend fun requestHoldingLock(pressed: DecisionVersion): GrantRequestOutcome {
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
        // Anything decided since the press (a decline while the deletion was sent, the census off and on again) wins:
        // nothing is sent.
        if (decisionVersion() != pressed || credential.installId != pressed.installId) return superseded()
        // The decision is recorded before the request, whatever the server answers; a receipt moves no version.
        store.update { it.copy(receipt = receipt(now, granted = true)) }
        val result = try {
            apis.create(settings.censusBaseUrl.first()).requestContributorGrant(credential.bearer())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            GrantResult.TransportFailure("Exception")
        }
        if (result == GrantResult.InstallRevoked) {
            revokeHoldingLock()
            return GrantRequestOutcome.INSTALL_REVOKED
        }
        val outcome = when (result) {
            is GrantResult.Granted -> GrantRequestOutcome.GRANTED
            GrantResult.InstallTooNew -> GrantRequestOutcome.INSTALL_TOO_NEW
            GrantResult.NotEligible -> GrantRequestOutcome.NOT_ELIGIBLE
            GrantResult.GrantRevoked -> GrantRequestOutcome.GRANT_REVOKED
            // The POST never answers these; a state read does. Either way nothing was granted.
            else -> return GrantRequestOutcome.UNREACHABLE
        }
        // The same version must still stand when the answer arrives; the generation is checked inside the write.
        val stands = decisionVersion().copy(generation = pressed.generation) == pressed
        var applied = false
        store.update { record ->
            if (!stands || record.generation != pressed.generation) return@update record
            applied = true
            when (result) {
                is GrantResult.Granted ->
                    record.granted(
                        ContributorGrant(credential.installId, result.grantedDay, result.expiresDay, now, pressed), now,
                    )
                GrantResult.InstallTooNew -> record.refused(GrantRefusal.INSTALL_TOO_NEW, now)
                GrantResult.NotEligible -> record.refused(GrantRefusal.NOT_ELIGIBLE, now)
                else -> record.refused(GrantRefusal.GRANT_REVOKED, now)
            }
        }
        // A grant the server issued after the dasher declined is never used: nothing on the phone holds it, so no
        // contributor upload can run under it, and it lapses on the server after its 30 days.
        if (!applied) return superseded()
        if (result == GrantResult.GrantRevoked) withContext(NonCancellable) { clearPendingReviews() }
        return outcome
    }

    private fun superseded(): GrantRequestOutcome {
        Timber.tag(TAG).i("census contributor request superseded")
        return GrantRequestOutcome.SUPERSEDED
    }

    /** `401 revoked` on a grant route: the census ID itself is revoked. The caller holds [CensusUploadLock]. */
    private suspend fun revokeHoldingLock() = withContext(NonCancellable) {
        val now = clock.millis()
        store.update { it.ended(ContributorEnd.INSTALL_REVOKED, now) }
        clearPendingReviews()
        try {
            revokeInstall()
            settings.setCensusLastRun(CensusLastRun(now, CensusRunOutcome.REVOKED))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Timber.tag(TAG).w("census contributor install revocation failed")
        }
        Timber.tag(TAG).w("census contributor ended reason=install_revoked")
    }

    /** The screen's answers run on the application scope: the screen closes on Not now and No thanks. */
    fun notNowAsync(): Job = scope.launch { notNow() }
    fun noThanksAsync(): Job = scope.launch { noThanks() }
    fun stopAsync(): Job = scope.launch { stop() }

    /** Not now: no repeat for [ContributorInvitation.NOT_NOW_DAYS] days. */
    suspend fun notNow() = withContext(NonCancellable) {
        val record = store.update { it.answered(InvitationAnswer.NOT_NOW, clock.millis()) }
        if (record.reviewsClearPending) clearPendingReviews()
    }

    /** No thanks: no invitation again until the dasher accepts from Settings. Recorded as a declined receipt. */
    suspend fun noThanks() = withContext(NonCancellable) {
        val now = clock.millis()
        val record = store.update { it.answered(InvitationAnswer.NO_THANKS, now, receipt(now, granted = false)) }
        if (record.reviewsClearPending) clearPendingReviews()
    }

    /** Stop reviewing: the grant ends on the phone now, recorded as a declined receipt and a No thanks. */
    suspend fun stop() = withContext(NonCancellable) {
        val now = clock.millis()
        store.update { it.ended(ContributorEnd.STOPPED, now, receipt(now, granted = false)) }
        clearPendingReviews()
        Timber.tag(TAG).i("census contributor ended reason=stopped")
    }

    /**
     * [ContributorGate]: true only while a grant stands. A grant that no longer stands is ended here. Takes no lock, so
     * an upload that holds the census upload lock can call it.
     */
    override suspend fun mayUpload(): Boolean = guarded(false) { reviewsCleared() && reconcile() }

    /**
     * Reads the grant's state on the server (`GET /v1/contributor/grant`), as the server's client guide asks before
     * each review session, and ends the phone's grant when the server says it expired, was revoked or is gone. A
     * `401 revoked` is the census ID itself revoked: the grant ends and the census goes off with the ID forgotten, as on
     * any census revoke. A granted answer only updates the days of the grant the phone already holds: it never creates
     * one, so a grant the dasher stopped stays stopped. Returns [mayUpload] afterwards. Takes [CensusUploadLock]: call it
     * before a review session, never from inside an upload that holds the lock.
     */
    suspend fun refresh(): Boolean = guarded(false) {
        lock.mutex.withLock {
            val grant = store.record.first().grant ?: return@withLock false
            if (!reconcile()) return@withLock false
            val credential = credentials.current()?.takeIf { it.installId == grant.installId } ?: return@withLock reconcile()
            when (val result = apis.create(settings.censusBaseUrl.first()).contributorGrant(credential.bearer())) {
                is GrantResult.Granted -> store.update { record ->
                    if (record.grant == grant) {
                        record.copy(grant = grant.copy(grantedDay = result.grantedDay, expiresDay = result.expiresDay))
                    } else record
                }
                GrantResult.Expired -> end(grant, ContributorEnd.EXPIRED)
                GrantResult.GrantRevoked -> end(grant, ContributorEnd.REVOKED)
                GrantResult.None -> end(grant, ContributorEnd.SERVER_NONE)
                GrantResult.InstallRevoked -> revokeHoldingLock()
                else -> Unit
            }
            reconcile()
        }
    }

    /**
     * App start: ends a grant whenever what it stands on changes (consent, census ID, a deletion request), and at the
     * instant its expiry day begins, with no other change needed.
     */
    fun watch(): Job = scope.launch {
        // A clear that a process death or a failure left outstanding runs again first.
        guarded(false) { reviewsCleared() }
        launch {
            combine(store.record, settings.censusConsent, credentials.installId, settings.censusDeletion) { record, _, _, _ ->
                record.grant
            }.catch { e ->
                // mayUpload checks again before any upload, so a watch that stops leaves nothing open.
                Timber.tag(TAG).w("census contributor watch stopped cause=%s", e.javaClass.simpleName)
            }.collect { grant -> if (grant != null) guarded(false) { reconcile() } }
        }
        launch {
            store.record.map { it.grant }.distinctUntilChanged().catch { e ->
                Timber.tag(TAG).w("census contributor expiry watch stopped cause=%s", e.javaClass.simpleName)
            }.collectLatest { grant ->
                val endsAt = grant?.expiresAtMillis() ?: return@collectLatest
                delay((endsAt - clock.millis()).coerceAtLeast(0))
                guarded(false) { reconcile() }
            }
        }
    }

    /** True when a live grant stands after the check; a grant that no longer stands is ended with its reason. */
    /**
     * True only when a grant stands: stored, before its expiry day, and decided under the current [DecisionVersion]. This
     * is the check itself, read from stored state, so it needs no watcher; a grant that does not stand is also ended here,
     * with the reason the screen shows (cleanup).
     */
    private suspend fun reconcile(): Boolean {
        val (record, current) = versions.first()
        val grant = record.grant ?: return false
        if (grant.standsOn(todayUtc(), current)) return true
        val made = grant.version
        val reason = when {
            settings.censusConsent.first() != CensusConsent.ON -> ContributorEnd.CENSUS_OFF
            made == null -> ContributorEnd.CENSUS_OFF
            current.installId != made.installId -> ContributorEnd.ID_CHANGED
            current.deletionRequestedAt != made.deletionRequestedAt -> ContributorEnd.DATA_DELETED
            current.consent != made.consent || current.endpoint != made.endpoint -> ContributorEnd.CENSUS_OFF
            current.generation != made.generation -> ContributorEnd.STOPPED
            else -> ContributorEnd.EXPIRED
        }
        end(grant, reason)
        return false
    }

    /** Ends [grant] (only that one: a newer grant stays) and clears the reviews waiting on the phone. */
    /**
     * Ends [grant] (only that one: a newer grant stays) and clears the reviews waiting on the phone, as one unit no
     * cancellation splits: ending the grant re-emits into the expiry watch's `collectLatest`, which must not cut the
     * clear short. The end's write also sets [ContributorRecord.reviewsClearPending], so a clear that still fails is run
     * again.
     */
    private suspend fun end(grant: ContributorGrant, reason: ContributorEnd) = withContext(NonCancellable) {
        val now = clock.millis()
        store.update { record -> if (record.grant == grant) record.ended(reason, now) else record }
        clearPendingReviews()
        Timber.tag(TAG).i("census contributor ended reason=%s", reason.name.lowercase())
    }

    /** Clears the reviews waiting on the phone, then (only on success) the record's pending-clear mark. */
    private suspend fun clearPendingReviews() {
        try {
            pendingReviews.clear()
            store.update { it.copy(reviewsClearPending = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Timber.tag(TAG).w("census contributor pending reviews clear failed; it runs again")
        }
    }

    /** True when no clear is outstanding, running an outstanding one first. */
    private suspend fun reviewsCleared(): Boolean {
        if (!store.record.first().reviewsClearPending) return true
        withContext(NonCancellable) { clearPendingReviews() }
        return !store.record.first().reviewsClearPending
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

        /** Once a minute, from the first collection on. */
        fun minuteTicks(): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(60_000L)
            }
        }
    }
}
