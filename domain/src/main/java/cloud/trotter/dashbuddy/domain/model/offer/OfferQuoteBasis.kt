package cloud.trotter.dashbuddy.domain.model.offer

import kotlinx.serialization.Serializable

/** Whether the platform quotes the whole offer or an increment to a live route. */
@Serializable
enum class OfferQuoteBasis { TOTAL, INCREMENTAL }
