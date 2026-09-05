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
     * Hash of the PRESENTATION identity — the subset that stays stable for as long as ONE
     * physical presentation of this surface is on screen, even while the surface live-updates
     * its own content (#859).
     *
     * [dedupeHash] answers "is this the same content?"; this answers "is this the same
     * showing?". They differ only on a re-quoting surface: an offer card that re-renders its
     * pay/miles/minutes every few seconds churns [dedupeHash] while the driver is looking at
     * ONE offer, so a per-content dedupe key re-fires the rule's effects several times per
     * offer. Shapes with no distinct presentation identity fall back to [dedupeHash], so the
     * default is exactly today's behaviour.
     */
    open fun presentationHash(): Int = dedupeHash()

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

        /**
         * The #830 `presentationKey` (sha256 of the STABLE subset — store names, order count,
         * order types) IS the offer's presentation identity: it is what the state machine
         * already uses to tell "the same offer, re-quoted" from "a different offer".
         *
         * Fail-CLOSED, exactly as #830 defines it: a null key (digest failure, or a frame with
         * no content-bearing stable subset) degrades to the per-quote [offerHash] — i.e. back
         * to today's churn — never to a shared constant that would merge distinct offers.
         */
        override fun presentationHash(): Int =
            (parsedOffer.presentationKey ?: parsedOffer.offerHash).hashCode()
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
            // #1029: `sessionEarnings` is a DIGIT-WHEEL read on this surface too ("This dash so
            // far"), and it is the ONLY field that moves while the receipt sits still. Without it
            // here, a re-render whose wheel has SETTLED carries an identity identical to the
            // mid-spin frame before it, `FrameGate.admit` drops it, and the receipt path can never
            // self-correct — the PR's own golden had approved a pre-roll $17.75 for a $35.47 dash
            // on exactly that shape. Cost: a spinning wheel admits a few extra frames (each a
            // capture), bounded by the spin. Verified safe against the #427 dedupeKey templates:
            // no post_task-shaped rule dedupes an effect on `{parsedHash}`/`{presentationHash}`
            // (only the two offer rules do), so no effect key moves with this.
            h = 31 * h + sessionEarnings.hashCode()
            return h
        }
    }

    @Serializable

    data class SessionEndedFields(
        override val activity: String? = null,
        /**
         * The summary screen's own parsed all-pay total — **nullable, and null means the parse
         * MISSED** (#1030). It used to be non-null with the factory coercing a miss to `0.0`, which
         * fabricated the exact value every downstream consumer reads as "the platform reported $0";
         * a genuine parsed `0.00` still arrives as `0.0` and is kept, which is the honest split.
         */
        val totalEarnings: Double? = null,
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

        // Null-safe (#1030): a missed parse hashes as 0 — the same bucket the pre-#1030 coerced
        // `0.0` produced, so frame dedupe behaviour is unchanged.
        override fun dedupeHash(): Int = totalEarnings?.hashCode() ?: 0
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
        /**
         * The dasher's headline points score on DoorDash's 0.230.0 points-based
         * rating redesign (#962) — a plain FACT, recorded so a later analytics
         * correlation ("did $/hr move after the tier changed?") has an anchor. Null
         * on any platform / layout that does not render one.
         */
        val overallRatingPoints: Int? = null,
        /**
         * The raw reward-tier label as the platform renders it (#962) — e.g.
         * "Silver". Deliberately a verbatim string, not an enum: the tier ladder is
         * one platform's own gamification vocabulary and modelling it as a
         * cross-platform concept would be forced (dev ruling 2026-07-30).
         */
        val tierLabel: String? = null,
        /** Rating factor introduced by the same redesign (#962); 0–100 percentage. */
        val qualityRate: Double? = null,
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
            "overallRatingPoints" to overallRatingPoints,
            "tierLabel" to tierLabel,
            "qualityRate" to qualityRate,
        )

        override fun dedupeHash(): Int {
            var h = customerRating.hashCode()
            h = 31 * h + lifetimeDeliveries.hashCode()
            // #962 — the redesigned hub renders no `lifetimeDeliveries` at all, so
            // without the points score two genuinely different snapshots of it
            // (73 pts vs 78 pts at an unchanged 5.00 star rating) would collide.
            h = 31 * h + overallRatingPoints.hashCode()
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
