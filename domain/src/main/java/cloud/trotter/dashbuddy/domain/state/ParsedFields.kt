package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedTime
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay

/** Known wire values for the open [ParsedFields.activity] tag (rule-emitted). */
object PickupActivity {
    const val SHOPPING = "shopping"
    const val CONFIRMED = "confirmed"
}

/**
 * Typed parsed data produced by rules and consumed by the state machine.
 * Each subtype corresponds to a [Flow] family — ParsedFieldsFactory (:core:pipeline)
 * validates that rule output conforms to the contract at load time.
 *
 * All subtypes carry an optional [activity] tag for platform-specific
 * refinements within a flow (e.g., "shopping", "scanning_card"). The tag is an
 * open, rule-emitted string; [PickupActivity] names the values the code keys on.
 */
@Serializable
sealed class ParsedFields {
    abstract val activity: String?

    /**
     * Hash of the stable identity fields for this observation.
     * Used for post-classification dedup — only fields that represent
     * semantic identity are included. Transient/ticking fields
     * (deadlines, timestamps, expanding state) are excluded.
     */
    open fun dedupeHash(): Int = 0

    /**
     * Structural fields for effect-gate evaluation (`onlyIf`, #345/#434) —
     * every constructor property EXCEPT the open [activity] discriminator
     * (rules gate on structural fields, not the classification tag).
     *
     * Hand-written and exhaustive over the sealed hierarchy, replacing the
     * old Java reflection in EffectMap: rename-proof under R8/minification
     * and cheaper on the hot diff path. `ParsedFieldsFieldMapTest` asserts
     * every subtype's map stays in sync with its constructor.
     */
    abstract fun toFieldMap(): Map<String, Any?>

    @Serializable

    data object None : ParsedFields() {
        override val activity: String? = null
        override fun toFieldMap(): Map<String, Any?> = emptyMap()
    }

    @Serializable

    data class IdleFields(
        override val activity: String? = null,
        val zoneName: String? = null,
        val sessionType: SessionType? = null,
        val sessionPay: Double? = null,
        val waitTimeEstimate: String? = null,
        val isHeadingBackToZone: Boolean = false,
        val spotSaveDeadline: Long? = null,
        /**
         * The dasher is starting/scheduling a new dash (e.g. the set-end-time
         * screen). Used by the state machine to end a just-finished dash that's
         * still in its grace window and start a fresh one rather than resuming.
         * Transient signal — excluded from [dedupeHash].
         */
        val startingSession: Boolean = false,
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = mapOf(
            "zoneName" to zoneName,
            "sessionType" to sessionType,
            "sessionPay" to sessionPay,
            "waitTimeEstimate" to waitTimeEstimate,
            "isHeadingBackToZone" to isHeadingBackToZone,
            "spotSaveDeadline" to spotSaveDeadline,
            "startingSession" to startingSession,
        )

        override fun dedupeHash(): Int {
            var h = zoneName.hashCode()
            h = 31 * h + sessionType.hashCode()
            h = 31 * h + sessionPay.hashCode()
            return h
        }
    }

    @Serializable

    data class OfferFields(
        override val activity: String? = null,
        val parsedOffer: ParsedOffer,
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = mapOf(
            "parsedOffer" to parsedOffer,
        )

        override fun dedupeHash(): Int = parsedOffer.offerHash.hashCode()
    }

    @Serializable

    data class TaskFields(
        override val activity: String? = null,
        val phase: TaskPhase,
        val subFlow: TaskSubFlow,
        val storeName: String? = null,
        val storeAddress: String? = null,
        val customerNameHash: String? = null,
        val customerAddressHash: String? = null,
        val deadline: ParsedTime? = null,
        val itemsRemaining: Int? = null,
        val itemsShopped: Int? = null,
        val redCardTotal: Double? = null,
        val arrivalConfirmed: Boolean = false,
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = mapOf(
            "phase" to phase,
            "subFlow" to subFlow,
            "storeName" to storeName,
            "storeAddress" to storeAddress,
            "customerNameHash" to customerNameHash,
            "customerAddressHash" to customerAddressHash,
            "deadline" to deadline,
            "itemsRemaining" to itemsRemaining,
            "itemsShopped" to itemsShopped,
            "redCardTotal" to redCardTotal,
            "arrivalConfirmed" to arrivalConfirmed,
        )

        override fun dedupeHash(): Int {
            var h = phase.hashCode()
            h = 31 * h + subFlow.hashCode()
            h = 31 * h + storeName.hashCode()
            h = 31 * h + arrivalConfirmed.hashCode()
            // Shopping progress IS semantic identity for Shop & Deliver. Without
            // the item counts here, the post-classification dedup
            // (AccessibilityPipeline: identity == lastIdentity -> drop) collapses
            // count-only changes — including the decisive "To shop (0)" / Done(total)
            // frame — so itemsShopped caps at total-1 and items/min finishes one
            // short. Null for non-shopping tasks, so this is a no-op there.
            // (field log 2026-06-05)
            h = 31 * h + itemsRemaining.hashCode()
            h = 31 * h + itemsShopped.hashCode()
            return h
        }
    }

    @Serializable

    data class PostTaskFields(
        override val activity: String? = null,
        val totalPay: Double,
        val appPay: Double? = null,
        val customerTips: Double? = null,
        val parsedPay: ParsedPay? = null,
        val isExpanded: Boolean = false,
        val expandButtonId: String? = null,
        val sessionEarnings: Double? = null,
        val offersAccepted: Int? = null,
        val offersTotal: Int? = null,
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = mapOf(
            "totalPay" to totalPay,
            "appPay" to appPay,
            "customerTips" to customerTips,
            "parsedPay" to parsedPay,
            "isExpanded" to isExpanded,
            "expandButtonId" to expandButtonId,
            "sessionEarnings" to sessionEarnings,
            "offersAccepted" to offersAccepted,
            "offersTotal" to offersTotal,
        )

        override fun dedupeHash(): Int {
            var h = totalPay.hashCode()
            h = 31 * h + appPay.hashCode()
            h = 31 * h + customerTips.hashCode()
            return h
        }
    }

    @Serializable

    data class SessionEndedFields(
        override val activity: String? = null,
        val totalEarnings: Double,
        val sessionDurationMillis: Long? = null,
        val offersAccepted: Int? = null,
        val offersTotal: Int? = null,
        val weeklyEarnings: Double? = null,
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = mapOf(
            "totalEarnings" to totalEarnings,
            "sessionDurationMillis" to sessionDurationMillis,
            "offersAccepted" to offersAccepted,
            "offersTotal" to offersTotal,
            "weeklyEarnings" to weeklyEarnings,
        )

        override fun dedupeHash(): Int = totalEarnings.hashCode()
    }

    @Serializable

    data class PausedFields(
        override val activity: String? = null,
        val remainingText: String? = null,
        val remainingMillis: Long? = null,
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = mapOf(
            "remainingText" to remainingText,
            "remainingMillis" to remainingMillis,
        )

        // Paused is a single state — identity is just "paused".
        override fun dedupeHash(): Int = "paused".hashCode()
    }

    @Serializable

    data class TimelineFields(
        override val activity: String? = null,
        val sessionEarnings: Double? = null,
        val offerEarnings: Double? = null,
        val endsAtText: String? = null,
        val endsAtMillis: Long? = null,
        val tasks: List<TimelineTaskEntry> = emptyList(),
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = mapOf(
            "sessionEarnings" to sessionEarnings,
            "offerEarnings" to offerEarnings,
            "endsAtText" to endsAtText,
            "endsAtMillis" to endsAtMillis,
            "tasks" to tasks,
        )

        override fun dedupeHash(): Int {
            var h = sessionEarnings.hashCode()
            h = 31 * h + tasks.size
            return h
        }
    }

    @Serializable

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
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = mapOf(
            "acceptanceRate" to acceptanceRate,
            "completionRate" to completionRate,
            "onTimeRate" to onTimeRate,
            "customerRating" to customerRating,
            "deliveriesLast30Days" to deliveriesLast30Days,
            "lifetimeDeliveries" to lifetimeDeliveries,
            "originalItemsFoundRate" to originalItemsFoundRate,
            "totalItemsFoundRate" to totalItemsFoundRate,
            "substitutionIssuesRate" to substitutionIssuesRate,
            "itemsWithQualityIssuesRate" to itemsWithQualityIssuesRate,
            "itemsWrongOrMissingRate" to itemsWrongOrMissingRate,
            "lifetimeShoppingOrders" to lifetimeShoppingOrders,
        )

        override fun dedupeHash(): Int {
            var h = customerRating.hashCode()
            h = 31 * h + lifetimeDeliveries.hashCode()
            return h
        }
    }

    @Serializable

    data class SensitiveFields(
        override val activity: String? = null,
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = emptyMap()
    }

    @Serializable

    data class NoiseFields(
        override val activity: String? = null,
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = emptyMap()
    }

    @Serializable

    data class ClickFields(
        override val activity: String? = null,
        val intent: String,
        val nodeId: String? = null,
        val nodeText: String? = null,
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = mapOf(
            "intent" to intent,
            "nodeId" to nodeId,
            "nodeText" to nodeText,
        )

        // Every click is unique — identity() returns null for ClickFields
        // observations (#366), an explicit never-dedupe signal.
    }

    @Serializable

    data class NotificationFields(
        override val activity: String? = null,
        val intent: String,
        val amount: Double? = null,
        val storeName: String? = null,
        val deliveredAt: String? = null,
        val rawText: String? = null,
    ) : ParsedFields() {
        override fun toFieldMap(): Map<String, Any?> = mapOf(
            "intent" to intent,
            "amount" to amount,
            "storeName" to storeName,
            "deliveredAt" to deliveredAt,
            "rawText" to rawText,
        )

        override fun dedupeHash(): Int {
            var h = intent.hashCode()
            h = 31 * h + amount.hashCode()
            h = 31 * h + storeName.hashCode()
            return h
        }
    }
}

/**
 * A single entry in a timeline task chain, extracted from the
 * dash-controls overlay.
 */
@Serializable
data class TimelineTaskEntry(
    val taskType: String,
    val nameHash: String?,
    val deadline: ParsedTime?,
    val storeHint: String?,
    val isCurrent: Boolean = false,
)
