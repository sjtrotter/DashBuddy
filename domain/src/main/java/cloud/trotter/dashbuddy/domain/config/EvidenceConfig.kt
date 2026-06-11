package cloud.trotter.dashbuddy.domain.config

data class EvidenceConfig(
    val masterEnabled: Boolean = DEFAULT_MASTER,
    val saveOffers: Boolean = DEFAULT_SAVE_OFFERS,
    val saveDeliverySummaries: Boolean = DEFAULT_SAVE_DELIVERIES,
    val saveDashSummaries: Boolean = DEFAULT_SAVE_DASHES,
) {
    companion object {
        // ONE owner for every default (#401).
        const val DEFAULT_MASTER = false
        const val DEFAULT_SAVE_OFFERS = true
        const val DEFAULT_SAVE_DELIVERIES = true
        const val DEFAULT_SAVE_DASHES = true
    }
}