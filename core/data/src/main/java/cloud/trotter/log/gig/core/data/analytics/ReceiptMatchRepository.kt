package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.ReceiptDao
import cloud.trotter.log.gig.core.database.analytics.ReceiptMatchDao
import cloud.trotter.log.gig.domain.analytics.receipts.ActiveMatchRow
import cloud.trotter.log.gig.domain.analytics.receipts.SavedReceipt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * One shift's saved receipts and its active receipt matches (#1599), read-model only; re-emits on every commit.
 * Callers judge it with the shift's own deliveries: `ShiftReceipts.of(read.receipts, detail.deliveries, read.active)`.
 */
@Singleton
class ReceiptMatchRepository @Inject constructor(
    private val receiptDao: ReceiptDao,
    private val matchDao: ReceiptMatchDao,
) {
    fun read(sessionId: String): Flow<ReceiptMatchRead> =
        combine(receiptDao.receiptsInSessionFlow(sessionId), matchDao.activeRowsInSessionFlow(sessionId)) { r, m ->
            ReceiptMatchRead(r.map { it.toSaved() }, m.map { it.toActive() })
        }
}

/** A shift's saved receipts and the rows its active matches wrote. */
data class ReceiptMatchRead(val receipts: List<SavedReceipt>, val active: List<ActiveMatchRow>)
