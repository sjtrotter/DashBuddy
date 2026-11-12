package cloud.trotter.log.gig.domain.model.event.payload

import kotlinx.serialization.Serializable

/** What a match did to one delivery (#1599). */
@Serializable
enum class ReceiptMatchRole { COVERED, CLEARED }

@Serializable
data class ReceiptMatchLine(
    /** `delivery_records` PK. */
    val deliveryEventSequenceId: Long,
    val role: ReceiptMatchRole,
    /** The pay the preview showed, in cents; null when the delivery had none. A record of what was replaced. */
    val priorPayCents: Long?,
    val priorPayBasis: String,
    /** COVERED: this delivery's share in cents. CLEARED: null (the estimate is removed). */
    val newPayCents: Long?,
)

/**
 * `RECEIPT_MATCHED` (#1599): the dasher's statement that the saved receipt [evidenceId] paid exactly the COVERED
 * deliveries. [commandId] is minted when the sheet opens, so a second write of one Confirm is a counted skip.
 * PII: the app's own ids and cents only.
 */
@Serializable
data class ReceiptMatchedPayload(
    val commandId: String,
    val sessionId: String,
    val evidenceId: String,
    val receiptCents: Long,
    val lines: List<ReceiptMatchLine>,
) : AppEventPayload

/** `RECEIPT_MATCH_UNDONE` (#1599): takes back the match [matchCommandId] of shift [sessionId]. */
@Serializable
data class ReceiptMatchUndonePayload(val sessionId: String, val matchCommandId: String) : AppEventPayload
