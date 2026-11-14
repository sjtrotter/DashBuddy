package cloud.trotter.log.gig.core.data.capability

import cloud.trotter.log.gig.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.log.gig.core.datastore.census.ContributorDataSource
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.log.gig.domain.capability.ConsentReceiptReader
import cloud.trotter.log.gig.domain.capability.StoredConsentReceipts
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * #170 — [ConsentReceiptReader] over the consent stores: one read of each, now. A storage failure propagates. The
 * census receipt (#1621) lives in the developer-settings store with the census switch; the screen-review receipt (#1625)
 * in the contributor record.
 */
@Singleton
class StoreConsentReceiptReader @Inject constructor(
    private val capabilities: RuleCapabilityDataSource,
    private val eventReceipt: EventReceiptConsentDataSource,
    private val devSettings: DevSettingsDataSource,
    private val contributor: ContributorDataSource,
) : ConsentReceiptReader {
    override suspend fun read(): StoredConsentReceipts = StoredConsentReceipts(
        screenEvents = eventReceipt.snapshot.first().receipt,
        capabilities = capabilities.snapshot().receipts,
        census = devSettings.censusConsent.first().receipt?.receipt,
        reviews = contributor.record.first().receipt,
    )
}
