package cloud.trotter.log.gig.core.data.census.contributor

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.contributor.ContributorRole
import cloud.trotter.census.contract.contributor.ContributorSlots
import cloud.trotter.census.contract.contributor.ContributorVariant
import cloud.trotter.census.contract.contributor.QuarantineNote
import cloud.trotter.census.contract.contributor.SlotClass
import cloud.trotter.census.contract.sha256OrNull
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.ContributorCall
import cloud.trotter.log.gig.core.network.census.ContributorRefusal
import cloud.trotter.log.gig.core.network.census.ContributorTransport
import cloud.trotter.log.gig.core.network.census.QuarantineReceipt
import cloud.trotter.log.gig.core.network.census.WordStatus
import cloud.trotter.log.gig.domain.census.contributor.ContributorGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** #1626: what a review row's slot sends: its public hash, `withheld`, a format template or a coarse kind. */
sealed interface SlotValue {
    /** A hash of possible app wording. The word itself stays on the phone. */
    data object Hashed : SlotValue
    /** A backstop or filter step withheld it: customer or personal data, or text too long to judge. */
    data object Covered : SlotValue
    /** A format with the digits removed, from the closed list (`$#.##`, `# mi`). */
    data class Format(val template: String) : SlotValue
    /** A number, a mix of letters and digits, or more than 8 words: no hash, only the kind. */
    data class Kind(val kind: String) : SlotValue
}

/**
 * #1626: one row of the review screen, in the census server's slot order. [word] is set only for a slot the server
 * calls constant whose word this phone still holds; every other row shows what leaves and no text, so a varying,
 * regional, unknown or withheld slot can never be unmasked.
 */
data class ReviewRow(
    val slot: String,
    val className: String?,
    val key: String,
    val value: SlotValue,
    val slotClass: SlotClass,
    val word: String?,
) {
    val confirmable: Boolean get() = word != null

    override fun toString(): String = "ReviewRow($slot, $slotClass, word=${if (word != null) "[redacted]" else "none"})"
}

/** #1626: how the slot classes came back. */
enum class ClassesState { LOADED, RELEASE_UNAVAILABLE, UNREACHABLE }

/** #1626: one screen ready for review. */
data class ScreenReview(
    val id: String,
    val day: String,
    val rows: List<ReviewRow>,
    val classes: ClassesState,
    /** False when the words were lost (gig.log restarted since it saw the screen): no row can show a word. */
    val wordsKept: Boolean,
)

/** #1626: opening a review. */
sealed interface ReviewLoad {
    data class Ready(val review: ScreenReview) : ReviewLoad
    /** No live grant: nothing is shown or sent. */
    data object NotAllowed : ReviewLoad
    /** Gone (sent, deleted, cleared or expired). */
    data object NotFound : ReviewLoad
    /** Uploaded today or not yet: the classes arrive with tomorrow's release. */
    data object NotReady : ReviewLoad
}

/** #1626: how a Send went. */
enum class SendOutcome {
    SENT,
    /** The contributor gate is closed (or the server said no grant): nothing was sent. */
    NOT_ALLOWED,
    NOTHING_TO_SEND,
    NOT_FOUND,
    /** The server paused uploads or a daily quota is spent: the review waits, try later. */
    LATER,
    UNREACHABLE,
    /** `401 revoked`: the census ID is revoked; the caller runs the contributor's refresh, which turns the census off. */
    INSTALL_REVOKED,
}

/** #1627: how an "Ask the developer" went. */
sealed interface AskOutcome {
    /** Quarantined, and the server's hash of what it sealed is the hash of the bytes this phone built. */
    data class Sent(val expiresDay: String) : AskOutcome
    /**
     * Quarantined, but the server's hash differs from the phone's: the sealed copy cannot be shown to be the outline
     * the screen described. Recorded (a WARN line) and never retried.
     */
    data class Unconfirmed(val expiresDay: String) : AskOutcome
    /** Nothing was quarantined. */
    data class NotSent(val reason: AskRefusal) : AskOutcome
}

/** #1627: why nothing was quarantined. */
enum class AskRefusal {
    /** The contributor gate is closed (or the server said no grant). */
    NOT_ALLOWED,
    NOT_FOUND,
    /** The server paused uploads or today's quota is spent. */
    LATER,
    UNREACHABLE,
    /** `422 bad_artifact` (or a `409` with no word in the artifact): the server could not take this screen. */
    REFUSED,
    /** `503 quarantine_unconfigured`: the server has no key to seal to. */
    UNCONFIGURED,
    /** `401 revoked`: the census ID is revoked. */
    INSTALL_REVOKED,
}

/**
 * #1626: the review session (ADR-0011 §15, census#81 `docs/CLIENT.md` "Contributor tier"). Every request runs under
 * [CensusUploadLock] and checks [ContributorGate.mayUpload] synchronously first, before each request; the caller runs
 * `CensusContributor.refresh()` (which takes the lock itself) before [open], never from inside.
 *
 * - [open]: retries the confirmed words held for their gate, then reads the slot classes of the screen's own variant
 *   (only for a candidate this phone uploaded on an earlier UTC day) and builds the rows.
 * - [send]: `review` (confirmations as public hashes, role tags) first; then `gates` for the confirmations; then
 *   `words` only for a (slot, hash) the gate reported open. A word whose gate stays closed is held in memory.
 * - [ask] (#1627): "Ask the developer". `gates` for the confirmations, then `quarantine` with the artifact
 *   [ContributorRequests.artifact] builds from the same skeleton, confirmations and role tags; the candidate then
 *   leaves the queue. No review is sent.
 */
@Singleton
class ContributorReviewer internal constructor(
    private val queue: ContributorReviewQueue,
    private val gate: ContributorGate,
    private val lock: CensusUploadLock,
    private val credential: suspend () -> Bearer.Credential?,
    private val transport: suspend () -> ContributorTransport,
    /** The grant's state read (`CensusContributor.refresh`); it takes the upload lock, so it runs only outside it. */
    private val refresh: suspend () -> Unit,
) {
    @Inject constructor(
        queue: ContributorReviewQueue,
        contributor: CensusContributor,
        lock: CensusUploadLock,
        credentials: CensusCredentialStore,
        settings: DevSettingsRepository,
        apis: CensusApiFactory,
    ) : this(
        queue,
        contributor,
        lock,
        { credentials.current()?.bearer() },
        { apis.contributor(settings.censusBaseUrl.first()) },
        { contributor.refresh() },
    )

    /**
     * The server said the grant is gone (`403 grant_required`) or the census ID is revoked (`401 revoked`) mid-session:
     * the session stops where it is. Thrown inside the lock, handled outside it ([abort]).
     */
    private class Aborted(val revoked: Boolean) : Exception()

    /**
     * After [Aborted]: the grant's state read runs first (outside the lock; it ends the grant and clears the queue when
     * the server says so), then the session's words and every held word are dropped whatever it found.
     */
    private suspend fun abort(id: String, revoked: Boolean) {
        Timber.tag(TAG).w("census review stopped cause=%s", if (revoked) "install_revoked" else "grant_required")
        try {
            refresh()
        } finally {
            queue.dropWords(id)
            queue.takeHeld()
        }
    }

    suspend fun open(id: String): ReviewLoad = try {
        lock.mutex.withLock { openHoldingLock(id) }
    } catch (a: Aborted) {
        abort(id, a.revoked)
        ReviewLoad.NotAllowed
    }

    private suspend fun openHoldingLock(id: String): ReviewLoad {
        if (!gate.mayUpload()) return ReviewLoad.NotAllowed
        val generation = queue.generation
        val cred = safeCredential()
        val api = transport()
        // Every session first asks again about the words held since an earlier Send, whichever screen it opens.
        if (cred != null) retryHeld(cred, api, generation)
        val entry = queue.candidate(id) ?: return ReviewLoad.NotFound
        if (!entry.readyOn(queue.todayUtc())) return ReviewLoad.NotReady
        val item = CensusSkeletonSchema.deserialize(entry.skeletonJson) as? UiSkeletonDto ?: return ReviewLoad.NotFound
        val variant = ContributorVariant.of(item) ?: return ReviewLoad.NotFound
        val slots = ContributorSlots.slots(item)
        val (classes, state) = when {
            cred == null || slots.isEmpty() -> emptyMap<String, SlotClass>() to ClassesState.UNREACHABLE
            !gate.mayUpload() -> return ReviewLoad.NotAllowed
            else -> {
                val ids = slots.map { it.id }.take(ContributorRequests.MAX_CLASS_SLOTS)
                when (val answer = api.classes(cred, ContributorRequests.classes(variant, ids))) {
                    is ContributorCall.Ok -> answer.value.bySlot.filterKeys { it in ids.toSet() } to ClassesState.LOADED
                    is ContributorCall.Refused -> when (answer.reason) {
                        ContributorRefusal.GRANT_REQUIRED -> throw Aborted(revoked = false)
                        ContributorRefusal.INSTALL_REVOKED -> throw Aborted(revoked = true)
                        ContributorRefusal.RELEASE_UNAVAILABLE -> emptyMap<String, SlotClass>() to ClassesState.RELEASE_UNAVAILABLE
                        else -> emptyMap<String, SlotClass>() to ClassesState.UNREACHABLE
                    }
                }
            }
        }
        val words = queue.wordsFor(id)
        val rows = slots.map { slot ->
            val slotClass = classes[slot.id] ?: SlotClass.UNKNOWN
            val value = valueOf(slot.slot.kind, slot.slot.h, slot.slot.shape)
            ReviewRow(
                slot = slot.id,
                className = slot.className,
                key = slot.key,
                value = value,
                slotClass = slotClass,
                // Only a hashed slot the server calls constant may show its word.
                word = if (value == SlotValue.Hashed && slotClass == SlotClass.CONSTANT) words?.get(slot.id) else null,
            )
        }
        return ReviewLoad.Ready(ScreenReview(entry.id, entry.day, rows, state, wordsKept = words != null))
    }

    /**
     * Sends the review of [id]: [confirmed] slot ids (each must be a hashed slot the screen showed as constant with its
     * word) and [roles] (any slot of the skeleton). The candidate leaves the queue once the server took the review.
     */
    suspend fun send(id: String, confirmed: Set<String>, roles: Map<String, ContributorRole>, shown: ScreenReview): SendOutcome =
        try {
            lock.mutex.withLock { sendHoldingLock(id, confirmed, roles, shown) }
        } catch (a: Aborted) {
            abort(id, a.revoked)
            if (a.revoked) SendOutcome.INSTALL_REVOKED else SendOutcome.NOT_ALLOWED
        }

    private suspend fun sendHoldingLock(id: String, confirmed: Set<String>, roles: Map<String, ContributorRole>, shown: ScreenReview): SendOutcome =
        run {
            if (!gate.mayUpload()) return@run SendOutcome.NOT_ALLOWED
            val generation = queue.generation
            val entry = queue.candidate(id) ?: return@run SendOutcome.NOT_FOUND
            val item = CensusSkeletonSchema.deserialize(entry.skeletonJson) as? UiSkeletonDto ?: return@run SendOutcome.NOT_FOUND
            val variant = ContributorVariant.of(item) ?: return@run SendOutcome.NOT_FOUND
            val (confirmations, tags) = choices(item, confirmed, roles, shown)
            if (confirmations.isEmpty() && tags.isEmpty()) return@run SendOutcome.NOTHING_TO_SEND
            val cred = safeCredential() ?: return@run SendOutcome.UNREACHABLE
            val api = transport()
            // Checked again right before the request: the grant may have ended while the credential was read.
            if (!gate.mayUpload()) return@run SendOutcome.NOT_ALLOWED
            when (val answer = api.review(cred, ContributorRequests.review(variant, confirmations, tags))) {
                is ContributorCall.Ok -> Unit
                is ContributorCall.Refused -> {
                    stopOn(answer.reason)
                    return@run outcomeOf(answer.reason)
                }
            }
            Timber.tag(TAG).i("census review sent confirmations=%d roles=%d", confirmations.size, tags.size)
            val words = queue.wordsFor(id)
            // The review landed: the candidate goes even if the gate check below fails; held words stay in memory.
            queue.remove(id)
            if (confirmations.isNotEmpty() && words != null) {
                val pending = confirmations.mapNotNull { c -> words[c.slot]?.let { ContributorReviewQueue.HeldWord(variant, c.slot, c.hash, it) } }
                gateAndSend(cred, api, pending, generation)
            }
            SendOutcome.SENT
        }

    /**
     * #1627: "Ask the developer" about [id]: the outbound skeleton the screen described, [confirmed] slots (only rows
     * [shown] as confirmable, as public hashes) and [roles], with an optional [note], sealed by the server to the
     * operator's key. A confirmed word goes only when `gates` answered open for that exact slot and hash in this call;
     * every other confirmation stays a hash. The phone hashes the exact artifact bytes it sent and compares them with
     * the server's `artifactSha256`. The candidate leaves the queue once the server stored the item.
     */
    suspend fun ask(
        id: String,
        confirmed: Set<String>,
        roles: Map<String, ContributorRole>,
        note: QuarantineNote?,
        shown: ScreenReview,
    ): AskOutcome = try {
        lock.mutex.withLock { askHoldingLock(id, confirmed, roles, note, shown) }
    } catch (a: Aborted) {
        abort(id, a.revoked)
        AskOutcome.NotSent(if (a.revoked) AskRefusal.INSTALL_REVOKED else AskRefusal.NOT_ALLOWED)
    }

    private suspend fun askHoldingLock(
        id: String,
        confirmed: Set<String>,
        roles: Map<String, ContributorRole>,
        note: QuarantineNote?,
        shown: ScreenReview,
    ): AskOutcome = run {
        fun refused(reason: AskRefusal) = AskOutcome.NotSent(reason)
        if (!gate.mayUpload()) return@run refused(AskRefusal.NOT_ALLOWED)
        if (shown.id != id) return@run refused(AskRefusal.NOT_FOUND)
        val entry = queue.candidate(id) ?: return@run refused(AskRefusal.NOT_FOUND)
        val item = CensusSkeletonSchema.deserialize(entry.skeletonJson) as? UiSkeletonDto ?: return@run refused(AskRefusal.NOT_FOUND)
        val variant = ContributorVariant.of(item) ?: return@run refused(AskRefusal.NOT_FOUND)
        val (confirmations, tags) = choices(item, confirmed, roles, shown)
        val cred = safeCredential() ?: return@run refused(AskRefusal.UNREACHABLE)
        val api = transport()
        val words = queue.wordsFor(id)
        // The gate is asked in this call: a word goes only for a (slot, hash) it answers open for right now.
        val open = if (confirmations.isEmpty() || words == null) {
            emptySet()
        } else {
            if (!gate.mayUpload()) return@run refused(AskRefusal.NOT_ALLOWED)
            when (val gated = api.gates(cred, ContributorRequests.gates(variant, confirmations))) {
                is ContributorCall.Ok -> gated.value.filter { it.open }.map { Confirmation(it.slot, it.hash) }.toSet()
                // No answer: every confirmation stays a hash.
                is ContributorCall.Refused -> emptySet<Confirmation>().also { stopOn(gated.reason) }
            }
        }
        var cleared = open
        var artifact = ContributorRequests.artifact(item, confirmations, tags, words, cleared)
        var answer = quarantine(api, cred, artifact, note) ?: return@run refused(AskRefusal.NOT_ALLOWED)
        // census#81: `409 gate_closed` stored nothing; the words go as their hashes instead, once.
        if (answer is ContributorCall.Refused && answer.reason == ContributorRefusal.GATE_CLOSED &&
            ContributorRequests.openWords(confirmations, words, cleared).isNotEmpty()
        ) {
            Timber.tag(TAG).w("census quarantine gate closed; asking again with hashes only")
            cleared = emptySet()
            artifact = ContributorRequests.artifact(item, confirmations, tags, words, cleared)
            answer = quarantine(api, cred, artifact, note) ?: return@run refused(AskRefusal.NOT_ALLOWED)
        }
        val receipt = when (answer) {
            is ContributorCall.Ok -> answer.value
            is ContributorCall.Refused -> {
                stopOn(answer.reason)
                return@run refused(askRefusalOf(answer.reason))
            }
        }
        // The item is stored: the candidate goes whether or not its hash matches.
        queue.remove(id)
        if (sha256OrNull(artifact) != receipt.artifactSha256) {
            Timber.tag(TAG).w("census quarantine unconfirmed cause=hash_mismatch")
            return@run AskOutcome.Unconfirmed(receipt.expiresDay)
        }
        Timber.tag(TAG).i(
            "census quarantine sent confirmations=%d roles=%d words=%d note=%b",
            confirmations.size, tags.size, ContributorRequests.openWords(confirmations, words, cleared).size, note != null,
        )
        AskOutcome.Sent(receipt.expiresDay)
    }

    /** One quarantine request, `mayUpload` checked right before it; null when the gate closed. Caller holds the lock. */
    private suspend fun quarantine(
        api: ContributorTransport,
        cred: Bearer.Credential,
        artifact: ByteArray,
        note: QuarantineNote?,
    ): ContributorCall<QuarantineReceipt>? {
        if (!gate.mayUpload()) return null
        return api.quarantine(cred, ContributorRequests.quarantine(artifact, note))
    }

    private fun askRefusalOf(reason: ContributorRefusal): AskRefusal = when (reason) {
        ContributorRefusal.GRANT_REQUIRED -> AskRefusal.NOT_ALLOWED
        ContributorRefusal.INSTALL_REVOKED -> AskRefusal.INSTALL_REVOKED
        ContributorRefusal.PAUSED, ContributorRefusal.BUDGET_EXHAUSTED, ContributorRefusal.RATE_LIMITED -> AskRefusal.LATER
        ContributorRefusal.BAD_ARTIFACT, ContributorRefusal.GATE_CLOSED, ContributorRefusal.BAD_REQUEST -> AskRefusal.REFUSED
        ContributorRefusal.QUARANTINE_UNCONFIGURED -> AskRefusal.UNCONFIGURED
        else -> AskRefusal.UNREACHABLE
    }

    /**
     * The dasher's choices as the server's items: confirmations only for rows [shown] as confirmable (each slot's public
     * hash, sorted, at most [ContributorRequests.MAX_GATE_ITEMS]) and role tags for slots of the skeleton.
     */
    private fun choices(
        item: UiSkeletonDto,
        confirmed: Set<String>,
        roles: Map<String, ContributorRole>,
        shown: ScreenReview,
    ): Pair<List<Confirmation>, List<Pair<String, ContributorRole>>> {
        val slots = ContributorSlots.slots(item).associateBy { it.id }
        val confirmable = shown.rows.filter { it.confirmable }.map { it.slot }.toSet()
        val confirmations = confirmed
            .filter { it in confirmable }
            .mapNotNull { slot -> slots[slot]?.slot?.h?.let { Confirmation(slot, it) } }
            .sortedBy { it.slot }
            .take(ContributorRequests.MAX_GATE_ITEMS)
        val tags = roles.filterKeys { it in slots }.toSortedMap().toList()
            .take(ContributorRequests.MAX_REVIEW_ITEMS - confirmations.size)
        return confirmations to tags
    }

    /** Drops the candidate and its words; nothing is sent. */
    suspend fun delete(id: String) = queue.remove(id)

    /** The confirmed words held since an earlier Send: their gates are asked again. Caller holds the lock. */
    private suspend fun retryHeld(cred: Bearer.Credential, api: ContributorTransport, generation: Long) {
        val held = queue.takeHeld()
        if (held.isNotEmpty()) gateAndSend(cred, api, held, generation)
    }

    /**
     * Asks the gate for each word's (slot, hash) and sends only the words it reports open; the rest are held again.
     * Caller holds the lock. A closed gate means the word never leaves the phone.
     */
    private suspend fun gateAndSend(
        cred: Bearer.Credential,
        api: ContributorTransport,
        words: List<ContributorReviewQueue.HeldWord>,
        generation: Long,
    ) {
        // Every word held again is held under the generation the session started with: after a clear it is dropped.
        suspend fun hold(word: ContributorReviewQueue.HeldWord) = queue.hold(word, generation)
        for ((variant, group) in words.groupBy { it.variant }) {
            for (chunk in group.chunked(ContributorRequests.MAX_GATE_ITEMS)) {
                if (!gate.mayUpload()) return
                val asked = chunk.map { Confirmation(it.slot, it.hash) }
                val answer = api.gates(cred, ContributorRequests.gates(variant, asked))
                if (answer !is ContributorCall.Ok) {
                    (answer as ContributorCall.Refused).reason.let(::stopOn)
                    chunk.forEach { hold(it) }
                    continue
                }
                val open = answer.value.filter { it.open }.map { it.slot to it.hash }.toSet()
                val (ready, closed) = chunk.partition { (it.slot to it.hash) in open }
                closed.forEach { hold(it) }
                for (send in ready.chunked(ContributorRequests.MAX_WORD_ITEMS)) {
                    // A failed grant check discards the words; they are never held again (Sol round 2 S12).
                    if (!gate.mayUpload()) return
                    val body = try {
                        ContributorRequests.words(variant, send.map { OpenWord(it.slot, it.hash, it.word) })
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    when (val sent = api.words(cred, body)) {
                        is ContributorCall.Ok -> {
                            val closedAgain = sent.value.filter { it.status == WordStatus.GATE_CLOSED }.map { it.slot }.toSet()
                            send.filter { it.slot in closedAgain }.forEach { hold(it) }
                            Timber.tag(TAG).i("census review words accepted=%d", sent.value.count { it.status == WordStatus.ACCEPTED })
                        }
                        is ContributorCall.Refused -> {
                            stopOn(sent.reason)
                            send.forEach { hold(it) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun safeCredential(): Bearer.Credential? = try {
        credential()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** A refusal that ends the session: no more requests, and [abort] runs outside the lock. */
    private fun stopOn(reason: ContributorRefusal) {
        if (reason == ContributorRefusal.GRANT_REQUIRED) throw Aborted(revoked = false)
        if (reason == ContributorRefusal.INSTALL_REVOKED) throw Aborted(revoked = true)
    }

    private fun outcomeOf(reason: ContributorRefusal): SendOutcome = when (reason) {
        ContributorRefusal.GRANT_REQUIRED -> SendOutcome.NOT_ALLOWED
        ContributorRefusal.PAUSED, ContributorRefusal.BUDGET_EXHAUSTED, ContributorRefusal.RATE_LIMITED -> SendOutcome.LATER
        ContributorRefusal.INSTALL_REVOKED -> SendOutcome.INSTALL_REVOKED
        else -> SendOutcome.UNREACHABLE
    }

    private companion object {
        const val TAG = "Census"

        fun valueOf(kind: String, hash: String?, shape: String?): SlotValue = when {
            hash != null -> SlotValue.Hashed
            kind == KindClassifier.WITHHELD -> SlotValue.Covered
            kind == KindClassifier.SHAPE && shape != null -> SlotValue.Format(shape)
            else -> SlotValue.Kind(kind)
        }
    }
}
