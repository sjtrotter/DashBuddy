package cloud.trotter.log.gig.core.network.census

import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.contributor.SlotClass

/**
 * #1626: the census server's screen-review routes (census#81, `docs/API.md` "Contributor tier"). Each call sends the
 * body it is given, byte for byte and signed over those bytes; the caller (`ContributorRequests`, `:core:data`) owns
 * what a body may hold. Only the closed fields below leave the transport.
 */
interface ContributorTransport {
    /** `POST /v1/contributor/classes`: the classes of the listed slots of one variant this install uploaded. */
    suspend fun classes(cred: Bearer.Credential, body: ByteArray): ContributorCall<SlotClasses>

    /** `POST /v1/contributor/review`: confirmations (slot and public hash) and role tags. Never a word. */
    suspend fun review(cred: Bearer.Credential, body: ByteArray): ContributorCall<ReviewCounts>

    /** `POST /v1/contributor/gates`: whether the word gate is open for each confirmed (slot, hash). */
    suspend fun gates(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<GateAnswer>>

    /** `POST /v1/contributor/words`: words whose gate the server reported open. */
    suspend fun words(cred: Bearer.Credential, body: ByteArray): ContributorCall<List<WordAnswer>>

    /** #1627 `POST /v1/contributor/quarantine`: "Ask the developer", the artifact sealed to the operator's key. */
    suspend fun quarantine(cred: Bearer.Credential, body: ByteArray): ContributorCall<QuarantineReceipt>

    companion object {
        const val CLASSES_PATH = "/v1/contributor/classes"
        const val REVIEW_PATH = "/v1/contributor/review"
        const val GATES_PATH = "/v1/contributor/gates"
        const val WORDS_PATH = "/v1/contributor/words"
        const val QUARANTINE_PATH = "/v1/contributor/quarantine"
    }
}

/** One contributor call: the parsed answer, or why there is none. */
sealed interface ContributorCall<out T> {
    data class Ok<T>(val value: T) : ContributorCall<T>
    data class Refused(val reason: ContributorRefusal, val retryAfterSeconds: Long? = null) : ContributorCall<Nothing>
}

/** Why a contributor call has no answer. Never carries server text. */
enum class ContributorRefusal {
    /** `403 grant_required`: no live grant on the server. */
    GRANT_REQUIRED,
    /** `503 paused`: the operator's pause switch (review, words and quarantine only). */
    PAUSED,
    /** `503 release_unavailable`: today's class release is not built yet (classes only). */
    RELEASE_UNAVAILABLE,
    /** `429 budget_exhausted`: a daily quota is spent. */
    BUDGET_EXHAUSTED,
    RATE_LIMITED,
    /** `401` other than revoked. */
    UNAUTHORIZED,
    /** `401 revoked`: the census ID itself is revoked. */
    INSTALL_REVOKED,
    BAD_REQUEST,
    /** #1627 `409 gate_closed`: a word in the quarantine artifact has no open gate; nothing was stored. */
    GATE_CLOSED,
    /** #1627 `422 bad_artifact`: the server could not validate the quarantine artifact; nothing was stored. */
    BAD_ARTIFACT,
    /** #1627 `503 quarantine_unconfigured`: the server has no operator key to seal to; nothing was stored. */
    QUARANTINE_UNCONFIGURED,
    SERVER_UNAVAILABLE,
    TRANSPORT_FAILURE,
    INVALID_RESPONSE,
}

/** The classes answer: the release day and a class per slot the server answered; an absent slot is unknown. */
data class SlotClasses(val releaseDay: String, val bySlot: Map<String, SlotClass>)

/** The review answer's counts. */
data class ReviewCounts(val confirmations: Int, val roles: Int, val skipped: Int)

/** One gate answer. */
data class GateAnswer(val slot: String, val hash: String, val open: Boolean)

/**
 * #1627: the quarantine answer: the UTC day the item is gone by (it expires at 00:00 UTC that day) and the SHA-256 of
 * the bytes the server sealed (64 lowercase hex).
 */
data class QuarantineReceipt(val expiresDay: String, val artifactSha256: String)

/** One word answer; [status] is the server's closed token. */
data class WordAnswer(val slot: String, val status: WordStatus)

enum class WordStatus(val wire: String) {
    ACCEPTED("accepted"),
    GATE_CLOSED("gate_closed"),
    HASH_MISMATCH("hash_mismatch"),
    NOT_CHROME("not_chrome"),
    ;

    companion object {
        fun fromWire(wire: String): WordStatus? = entries.firstOrNull { it.wire == wire }
    }
}
