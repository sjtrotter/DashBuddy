package cloud.trotter.dashbuddy.data.offer

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.dash.DashEntity
import java.util.Date

/** Represents an offer in the database.
 * @property id The unique identifier for the offer.
 * @property dashId The ID of the [DashEntity] session this offer belongs to.
 * @property offerHash A hash generated from core offer details (store, pay, distance, type, items) for quick comparison or grouping.
 * @property timestamp Timestamp (milliseconds since epoch) when this offer was first detected.
 * @property storeName The name of the store or restaurant. E.g., "Walgreens", "Pizza Hut".
 * @property orderType Type of order. E.g., "Pickup", "Shop for items", "Retail pickup".
 * @property itemCount For "Shop for items" orders, the number of items.
 * @property payAmount The numeric value of the guaranteed pay for the offer.
 * @property payTextRaw The full, raw text of the pay information. E.g., "$7.75 Guaranteed (incl. tips)", "$8.50+ Total will be higher".
 * @property isPayEstimate Flag indicating if the payTextRaw suggests the total could be higher (e.g., contains "+").
 * @property distanceMiles The numeric distance in miles for the offer.
 * @property distanceTextRaw The full, raw text of the distance. E.g., "2.8 mi".
 * @property dueByTimeText The "Deliver by" or "Pickup by" time as displayed on the offer, as a string. E.g., "Deliver by 8:52 PM".
 * @property dueByTimeMillis The "Deliver by" or "Pickup by" time converted to milliseconds since epoch, if possible.
 * @property isHighPayingOffer Flag indicating if the offer was marked as "High paying offer!".
 * @property isPriorityOffer Flag indicating if the offer was marked as a priority offer (e.g., "Your Platinum status gave you priority...").
 * @property isRedCardRequired Flag indicating if a Red Card is required for this offer.
 * @property status Status of the offer. E.g., "SEEN", "ACCEPTED", "DECLINED_USER", "DECLINED_TIMEOUT", "MISSED".
 * @property initialCountdownSeconds The initial countdown timer value displayed on the offer screen (e.g., 36 seconds).
 * @property zoneContext The zone in which this offer was received or is primarily for (if identifiable at offer time).
 * @property calculatedScore Your app's calculated score for this offer.
 * @property userDefinedQuality User-defined quality or notes for this offer.
 * @property rawExtractedTexts Store the full extracted text array (joined or as JSON) for this offer screen for later review or parsing.
 */
@Entity(
    tableName = "offers",
    foreignKeys = [
        ForeignKey(
            entity = DashEntity::class,
            parentColumns = ["id"],
            childColumns = ["dashId"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [Index(value = ["dashId"])]
)

data class OfferEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The ID of the Dash session this offer belongs to. */
    val dashId: Long,

    /** A hash generated from core offer details (store, pay, distance, type, items) for quick comparison or grouping. */
    val offerHash: String,

    /** Timestamp (milliseconds since epoch) when this offer was first detected. */
    val timestamp: Long = Date().time,

    /** The name of the store or restaurant. E.g., "Walgreens", "Pizza Hut". */
    val storeName: String? = null,

    /** Type of order. E.g., "Pickup", "Shop for items", "Retail pickup". */
    val orderType: String? = null,

    /** For "Shop for items" orders, the number of items. */
    val itemCount: Int? = null,

    /** The numeric value of the guaranteed pay for the offer. */
    val payAmount: Double? = null,

    /** The full, raw text of the pay information. E.g., "$7.75 Guaranteed (incl. tips)", "$8.50+ Total will be higher". */
    val payTextRaw: String? = null,

    /** Flag indicating if the payTextRaw suggests the total could be higher (e.g., contains "+"). */
    val isPayEstimate: Boolean = false,

    /** The numeric distance in miles for the offer. */
    val distanceMiles: Double? = null,

    /** The full, raw text of the distance. E.g., "2.8 mi". */
    val distanceTextRaw: String? = null,

    /** The "Deliver by" or "Pickup by" time as displayed on the offer, as a string. E.g., "Deliver by 8:52 PM". */
    val dueByTimeText: String? = null,

    /** The "Deliver by" or "Pickup by" time converted to milliseconds since epoch, if possible. */
    val dueByTimeMillis: Long? = null,

    /** Flag indicating if the offer was marked as "High paying offer!". */
    val isHighPayingOffer: Boolean = false,

    /** Flag indicating if the offer was marked as a priority offer (e.g., "Your Platinum status gave you priority..."). */
    val isPriorityOffer: Boolean = false,

    /** Flag indicating if a Red Card is required for this offer. */
    val isRedCardRequired: Boolean = false,

    /** * Status of the offer. E.g., "SEEN", "ACCEPTED", "DECLINED_USER", "DECLINED_TIMEOUT", "MISSED".
     * Using a String or an Enum converted to String. Replaces separate boolean flags.
     */
    val status: String = "SEEN", // Default to SEEN when first logged

    /** The initial countdown timer value displayed on the offer screen (e.g., 36 seconds). */
    val initialCountdownSeconds: Int? = null,

    /** The zone in which this offer was received or is primarily for (if identifiable at offer time). */
    val zoneContext: String? = null, // e.g., "TX: Leon Valley" - might come from ActiveDashSession at time of offer

    /** Your app's calculated score for this offer. */
    val calculatedScore: Double? = null,

    /** User-defined quality or notes for this offer. */
    val userDefinedQuality: String? = null,

    /** Store the full extracted text array (joined or as JSON) for this offer screen for later review or parsing. */
    val rawExtractedTexts: String? = null,
)