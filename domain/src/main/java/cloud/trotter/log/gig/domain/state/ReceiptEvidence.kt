package cloud.trotter.log.gig.domain.state

import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import kotlinx.serialization.Serializable

/**
 * What a post-delivery receipt read may pay (#1598). [PROVEN_SINGLE]: the app can prove the one delivery the
 * receipt paid for, so it pays that delivery alone. [UNPROVEN]: it pays nothing on its own and is kept as evidence.
 */
@Serializable
enum class ReceiptScope { PROVEN_SINGLE, UNPROVEN }

/** The first proof clause a receipt read failed (#1598), for the evidence record and the DEBUG line. */
@Serializable
enum class ReceiptProofGap {
    NO_SUBJECT, NO_LIVE_JOB, OFFER_COUNT, CARD_COUNT_MISSING, CARD_COUNT_NOT_ONE,
    SUBJECT_NOT_ACCOUNTABLE, OTHER_DROPS, STORE_MISSING, STORE_MISMATCH,
}

/** The receipt evidence episode this region is reading (#1598); see [PlatformRegion.openReceiptEvidence]. */
@Serializable
data class OpenReceiptEvidence(
    val evidenceId: String,
    val subjectTaskId: String,
    val jobId: String,
    val firstSeenAt: Long,
    val revision: Int,
    val lastTotalCents: Long,
    val lastParsedPay: ParsedPay? = null,
    val lastScope: ReceiptScope,
    /** [PlatformRegion.possibleWorkSeq] when this receipt was first read — the late-frame guard's reference. */
    val possibleWorkSeqAtOpen: Long,
)
