package cloud.trotter.dashbuddy.domain.config

data class EvidenceConfig(
    val masterEnabled: Boolean = false,
    val saveOffers: Boolean = true,
    val saveDeliverySummaries: Boolean = true,
    val saveDashSummaries: Boolean = true
)