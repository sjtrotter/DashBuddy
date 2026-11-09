package cloud.trotter.log.gig.core.data.capability

import cloud.trotter.log.gig.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.log.gig.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.log.gig.domain.capability.ConsentReceiptReader
import cloud.trotter.log.gig.domain.capability.StoredConsentReceipts
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * #170 — [ConsentReceiptReader] over the two consent stores: one read of each, now. A storage failure propagates.
 */
@Singleton
class StoreConsentReceiptReader @Inject constructor(
    private val capabilities: RuleCapabilityDataSource,
    private val eventReceipt: EventReceiptConsentDataSource,
) : ConsentReceiptReader {
    override suspend fun read(): StoredConsentReceipts = StoredConsentReceipts(
        screenEvents = eventReceipt.snapshot.first().receipt,
        capabilities = capabilities.snapshot().receipts,
    )
}
