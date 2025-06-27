package cloud.trotter.dashbuddy.data.dash

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.zone.ZoneEntity
import java.util.Date

/** Represents a dash session in the database.
 * @property id The unique identifier for the dash session.
 * @property startTime Timestamp (milliseconds since epoch) when this dash session officially started.
 * @property stopTime Timestamp (milliseconds since epoch) when this dash session ended. Nullable if somehow not recorded.
 * @property totalTime Total duration of the dash in milliseconds. Can be calculated (endTimeMillis - startTimeMillis).
 * @property activeTime Active time during the dash in milliseconds (time spent on deliveries). From Dash Summary.
 * @property totalDistance Total distance traveled during this dash.
 * @property activeDistance Total distance traveled while on a delivery during this dash.
 * @property dashType Earning mode for this dash, e.g., "Per Offer", "By Time".
 * @property totalEarnings Final total earnings for this dash from the Dash Summary.
 * @property doordashPay Breakdown of earnings: Base pay from DoorDash.
 * @property customerTips Breakdown of earnings: Total tips received.
 * @property deliveriesCompleted Total number of deliveries completed in this dash (from Dash Summary).
 * @property offersReceived Total number of offers received during this dash (can be aggregated).
 * @property offersAccepted Total number of offers accepted during this dash (can be aggregated).
 * @property notes User-added notes or comments about this dash session.
 */
@Entity(
    tableName = "dashes",
    foreignKeys = [
        ForeignKey(
            entity = ZoneEntity::class,
            parentColumns = ["id"],
            childColumns = ["zoneId"],
            onDelete = ForeignKey.SET_NULL // Or RESTRICT if you don't want zones deleted if referenced
        ),
    ],
    indices = [Index(value = ["zoneId"])]
)
data class DashEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Timestamp (milliseconds since epoch) when this dash session officially started. */
    val startTime: Long = Date().time,

    /** Timestamp (milliseconds since epoch) when this dash session ended. Nullable if somehow not recorded. */
    val stopTime: Long? = null,

    /** The zone where the dasher started the dash. */
    val zoneId: Long? = null,

    /** Total duration of the dash in hours. */
    val totalTime: Long? = null,

    /** Active time during the dash in milliseconds (time spent on deliveries). From Dash Summary. */
    val activeTime: Long? = null,

    /** Total distance traveled during this dash. */
    val totalDistance: Double? = null,

    /** Total distance traveled while on a delivery during this dash. */
    val activeDistance: Double? = null,

    /** Earning mode for this dash, e.g., "Per Offer", "By Time". */
    val dashType: DashType? = DashType.PER_OFFER,

    /** Final total earnings for this dash from the Dash Summary. */
    val totalEarnings: Double? = null,

    /** Breakdown of earnings: Base pay from DoorDash. */
    val doordashPay: Double? = null,

    /** Breakdown of earnings: Total tips received. */
    val customerTips: Double? = null,

    /** Total number of deliveries completed in this dash (from Dash Summary). */
    val deliveriesCompleted: Int? = null,

    /** Total number of offers received during this dash (can be aggregated). */
    val offersReceived: Int? = null,

    /** Total number of offers accepted during this dash (can be aggregated). */
    val offersAccepted: Int? = null,

    /** User-added notes or comments about this dash session. */
    val notes: String? = null,
)