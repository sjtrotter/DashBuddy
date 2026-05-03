package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedTime
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay

/**
 * Typed parsed data produced by rules and consumed by the state machine.
 * Each subtype corresponds to a [Flow] family — the [FieldsFactory]
 * validates that rule output conforms to the contract at load time.
 *
 * All subtypes carry an optional [activity] tag for platform-specific
 * refinements within a flow (e.g., "shopping", "scanning_card").
 */
sealed class ParsedFields {
    abstract val activity: String?

    data object None : ParsedFields() {
        override val activity: String? = null
    }

    data class IdleFields(
        override val activity: String? = null,
        val zoneName: String? = null,
        val sessionType: SessionType? = null,
        val sessionPay: Double? = null,
        val waitTimeEstimate: String? = null,
        val isHeadingBackToZone: Boolean = false,
        val spotSaveDeadline: Long? = null,
    ) : ParsedFields()

    data class OfferFields(
        override val activity: String? = null,
        val parsedOffer: ParsedOffer,
    ) : ParsedFields()

    data class TaskFields(
        override val activity: String? = null,
        val phase: TaskPhase,
        val subFlow: TaskSubFlow,
        val storeName: String? = null,
        val storeAddress: String? = null,
        val customerNameHash: String? = null,
        val customerAddressHash: String? = null,
        val deadline: ParsedTime? = null,
        val itemCount: Int? = null,
        val redCardTotal: Double? = null,
        val arrivalConfirmed: Boolean = false,
    ) : ParsedFields()

    data class PostTaskFields(
        override val activity: String? = null,
        val totalPay: Double,
        val doorDashPay: Double? = null,
        val customerTips: Double? = null,
        val parsedPay: ParsedPay? = null,
        val isExpanded: Boolean = false,
        val expandButtonId: String? = null,
        val sessionEarnings: Double? = null,
        val offersAccepted: Int? = null,
        val offersTotal: Int? = null,
    ) : ParsedFields()

    data class SessionEndedFields(
        override val activity: String? = null,
        val totalEarnings: Double,
        val sessionDurationMillis: Long? = null,
        val offersAccepted: Int? = null,
        val offersTotal: Int? = null,
        val weeklyEarnings: Double? = null,
    ) : ParsedFields()

    data class PausedFields(
        override val activity: String? = null,
        val remainingText: String,
        val remainingMillis: Long,
    ) : ParsedFields()

    data class TimelineFields(
        override val activity: String? = null,
        val sessionEarnings: Double? = null,
        val offerEarnings: Double? = null,
        val endsAtText: String? = null,
        val endsAtMillis: Long? = null,
        val tasks: List<TimelineTaskEntry> = emptyList(),
    ) : ParsedFields()

    data class RatingsFields(
        override val activity: String? = null,
        val acceptanceRate: Double? = null,
        val completionRate: Double? = null,
        val onTimeRate: Double? = null,
        val customerRating: Double? = null,
        val deliveriesLast30Days: Int? = null,
        val lifetimeDeliveries: Int? = null,
        val originalItemsFoundRate: Double? = null,
        val totalItemsFoundRate: Double? = null,
        val substitutionIssuesRate: Double? = null,
        val itemsWithQualityIssuesRate: Double? = null,
        val itemsWrongOrMissingRate: Double? = null,
        val lifetimeShoppingOrders: Int? = null,
    ) : ParsedFields()

    data class SensitiveFields(
        override val activity: String? = null,
    ) : ParsedFields()

    data class ClickFields(
        override val activity: String? = null,
        val intent: String,
        val nodeId: String? = null,
        val nodeText: String? = null,
    ) : ParsedFields()

    data class NotificationFields(
        override val activity: String? = null,
        val intent: String,
        val amount: Double? = null,
        val storeName: String? = null,
        val deliveredAt: String? = null,
        val rawText: String? = null,
    ) : ParsedFields()
}

/**
 * A single entry in a timeline task chain, extracted from the
 * dash-controls overlay.
 */
data class TimelineTaskEntry(
    val taskType: String,
    val nameHash: String?,
    val deadline: ParsedTime?,
    val storeHint: String?,
    val isCurrent: Boolean = false,
)
