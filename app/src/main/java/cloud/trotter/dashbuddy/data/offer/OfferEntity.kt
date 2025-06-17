package cloud.trotter.dashbuddy.data.offer

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.zone.ZoneEntity
import java.util.Date

/** Represents an offer in the database.
 * @property id The unique identifier for the offer.
 * @property dashId The ID of the [DashEntity] session this offer belongs to.
 * @property zoneId The zone in which this offer was received or is primarily for (if identifiable at offer time).
 * @property offerHash A hash generated from core offer details (store, pay, distance, type, items) for quick comparison or grouping.
 * @property timestamp Timestamp (milliseconds since epoch) when this offer was first detected.
 * @property itemCount For "Shop for items" orders, the number of items.
 * @property payAmount The numeric value of the guaranteed pay for the offer.
 * @property payTextRaw The full, raw text of the pay information. E.g., "$7.75 Guaranteed (incl. tips)", "$8.50+ Total will be higher".
 * @property isPayHigherIndicated Flag indicating if the payTextRaw suggests the total could be higher (e.g., contains "+").
 * @property distanceMiles The numeric distance in miles for the offer.
 * @property distanceTextRaw The full, raw text of the distance. E.g., "2.8 mi".
 * @property dueByTimeText The "Deliver by" or "Pickup by" time as displayed on the offer, as a string. E.g., "Deliver by 8:52 PM".
 * @property dueByTimeMillis The "Deliver by" or "Pickup by" time converted to milliseconds since epoch, if possible.
 * @property badges Offer-level badges found in the offer.
 * @property status Status of the offer. E.g., "SEEN", "ACCEPTED", "DECLINED_USER", "DECLINED_TIMEOUT", "MISSED".
 * @property initialCountdownSeconds The initial countdown timer value displayed on the offer screen (e.g., 36 seconds).
 * @property calculatedScore Your app's calculated score for this offer.
 * @property scoreText User-defined quality or notes for this offer.
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
        ForeignKey(
            entity = ZoneEntity::class,
            parentColumns = ["id"],
            childColumns = ["zoneId"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [
        Index(value = ["dashId"]),
        Index(value = ["zoneId"]),
        Index(value = ["offerHash"]),
    ]
)

data class OfferEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The ID of the Dash session this offer belongs to. */
    val dashId: Long,

    /** The active zone in the dash. (slight de-normalization)
     * meant to represent the zone the dasher is attached to, to give
     * context to what you can be offered when assigned to that zone.
     */
    val zoneId: Long,

    /** A hash generated from core offer details
     *  (store, pay, distance, type, items)
     *  for quick comparison or grouping. */
    val offerHash: String,

    /** Timestamp (milliseconds since epoch) when this offer was first detected. */
    val timestamp: Long = Date().time,

    // -- Order Details --
    /** For "Shop for items" orders, the number of items. Set to 1 for pickup order types. */
    val itemCount: Int = 1,

    /** The numeric value of the guaranteed pay for the offer. */
    val payAmount: Double? = null,

    /**
     *  The full, raw text of the pay information. E.g.,
     *  "$7.75 Guaranteed (incl. tips)"
     *  "$8.50+ Total will be higher".
     */
    val payTextRaw: String? = null,

    /** Flag indicating if the payTextRaw suggests the total could be higher (e.g., contains "+"). */
    val isPayHigherIndicated: Boolean = false,

    /** The numeric distance in miles for the offer. */
    val distanceMiles: Double? = null,

    /** The full, raw text of the distance. E.g., "2.8 mi". */
    val distanceTextRaw: String? = null,

    /**
     * The "Deliver by" time as displayed on the offer, as a string.
     *  E.g., "Deliver by 8:52 PM".
     */
    val dueByTimeText: String? = null,

    /** The "Deliver by" time converted to milliseconds since epoch, if possible. */
    val dueByTimeMillis: Long? = null,

    /** Offer-level badges found in the offer. */
    val badges: Set<OfferBadge> = emptySet(),

    /**
     * Status of the offer. E.g., "SEEN", "ACCEPTED", "DECLINED_USER", "DECLINED_TIMEOUT", "MISSED".
     * Using a String or an Enum converted to String. Replaces separate boolean flags.
     */
    val status: OfferStatus = OfferStatus.SEEN, // Default to SEEN when first logged

    /** The initial countdown timer value displayed on the offer screen (e.g., 36 seconds). */
    val initialCountdownSeconds: Int? = null,

    /** Calculated fields to determine score. */
    val dollarsPerMile: Double? = null,
    val dollarsPerHour: Double? = null,

    /** Your app's calculated score for this offer. */
    val calculatedScore: Double? = null,

    /** User-defined quality or notes for this offer. */
    val scoreText: String? = null,

    /** Store the full extracted text array (joined or as JSON) for this offer screen for later review or parsing. */
    val rawExtractedTexts: String? = null,
)