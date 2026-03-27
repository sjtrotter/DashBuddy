package cloud.trotter.dashbuddy.core.datastore.strategy.dto

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@InternalSerializationApi
@Serializable
sealed class ScoringRuleDto {
    abstract val id: String
    abstract val isEnabled: Boolean

    @Serializable
    @SerialName("metric")
    data class MetricRuleDto(
        override val id: String,
        override val isEnabled: Boolean = true,
        val metricType: String, // Stringified Enum
        val targetValue: Float,
        val autoDeclineOnFail: Boolean = false
    ) : ScoringRuleDto()

    @Serializable
    @SerialName("merchant")
    data class MerchantRuleDto(
        override val id: String,
        override val isEnabled: Boolean = true,
        val storeName: String,
        val action: String // Stringified Enum
    ) : ScoringRuleDto()
}