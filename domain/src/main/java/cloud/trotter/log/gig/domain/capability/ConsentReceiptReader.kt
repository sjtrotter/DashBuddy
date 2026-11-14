package cloud.trotter.log.gig.domain.capability

/**
 * #170 — the consent receipts as stored: the Screen events receipt, each capability's by key, the census's (#1621) and
 * the screen-review decision's (#1625).
 */
data class StoredConsentReceipts(
    val screenEvents: ConsentReceipt?,
    val capabilities: Map<String, ConsentReceipt>,
    val census: ConsentReceipt? = null,
    val reviews: ConsentReceipt? = null,
)

/**
 * #170 — reads the stored consent receipts from their stores at the moment of the call, for the bug-report export.
 * [RuleCapabilityGrants.receipts] and `EventReceiptPreferences.receipt` are caches that start empty and fill
 * asynchronously, so an early read of them cannot tell "no choices" from "not read yet". This read suspends until
 * both stores answer and throws when either cannot be read; the caller reports the records as unavailable then.
 * A record that is stored but undecodable reads as absent, as everywhere else (`RuleCapabilityDataSource`).
 */
interface ConsentReceiptReader {
    suspend fun read(): StoredConsentReceipts
}
