package cloud.trotter.dashbuddy.model.config

data class EvidenceConfig(
    val masterEnabled: Boolean = false,
    val saveOffers: Boolean = true,
    val saveDeliverySummaries: Boolean = true,
    val saveDashSummaries: Boolean = true
)