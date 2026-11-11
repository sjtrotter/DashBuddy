package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per receipt evidence record (#1598), folded from `RECEIPT_OBSERVED` and upserted by [evidenceId]: each
 * revision event carries the whole record, and events fold in sequence order, so REPLACE keeps the latest.
 *
 * Evidence, never money: no read sums these into pay. The dasher's matching sheet (#1599) reads [evidenceId],
 * [totalCents], [subjectTaskId] + [jobId] (joined to `delivery_records.taskId`/`jobId`) and [firstSeenAt]. Holds no
 * customer data; not in the CSV export.
 */
@Entity(
    tableName = "receipt_records",
    indices = [
        Index("sessionId"),
        Index("jobId"),
        Index("subjectTaskId"),
    ],
)
data class ReceiptRecordEntity(
    /** `receipt:<subjectTaskId>:<firstSeenAt>` — stable across the record's revisions. */
    @PrimaryKey val evidenceId: String,
    val sessionId: String?,
    /** Platform.wire. */
    val platform: String,
    val jobId: String,
    /** The drop the receipt appeared after. */
    val subjectTaskId: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val totalCents: Long,
    val basePayCents: Long?,
    val tipCents: Long?,
    val isExpanded: Boolean,
    /** `ReceiptScope.name`: PROVEN_SINGLE or UNPROVEN. */
    val scope: String,
    /** `ReceiptProofGap.name`; null when proven. */
    val proofGap: String?,
    val revision: Int,
    /** sequenceId of the latest `RECEIPT_OBSERVED` folded into this row. */
    val lastEventSequenceId: Long,
)
