package cloud.trotter.dashbuddy.data.dash

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/** Represents a dash session in the database.
 * @property id The unique identifier for the dash session.
 * @property startTime Timestamp (milliseconds since epoch) when this dash session officially started.
 * @property stopTime Timestamp (milliseconds since epoch) when this dash session ended. Nullable if somehow not recorded.
 * @property totalDistance Total distance traveled during this dash.
 * @property dashType Earning mode for this dash, e.g., "Per Offer", "By Time".
 * @property hourlyRate If dash type is By Time, we should store the $/hour rate.
 */
@Entity(
    tableName = "dashes",
    indices = [Index(value = ["startTime"]), Index(value = ["stopTime"])]
)
data class DashEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Timestamp (milliseconds since epoch) when this dash session officially started. */
    val startTime: Long = Date().time,

    /** Timestamp (milliseconds since epoch) when this dash session ended. Nullable if somehow not recorded. */
    val stopTime: Long? = null,

    /** Total distance traveled during this dash. */
    val totalDistance: Double? = null,

    /** Earning mode for this dash, e.g., "Per Offer", "By Time". */
    val dashType: DashType? = DashType.PER_OFFER,

    /** If dash type is By Time, we should store the $/hour rate. */
    val hourlyRate: Double? = null,

    /** The zone where the dasher started the dash.
     * -- This should be looked up in the DashZoneLink table.
     *    val zoneId: Long? = null,
     */

    /** Total duration of the dash in hours.
     * -- This should be calculated from the start and stop time of this dash.
     *    val totalTime: Long? = null,
     */

    /** Active time during the dash in milliseconds (time spent on deliveries).
     * -- This should be calculated from the orders associated with this dash.
     * -- The same goes for activeDistance.
     *    val activeTime: Long? = null,
     *    val activeDistance: Double? = null,
     */

    /** Final total earnings for this dash.
     * -- This should be calculated from the child AppPays and Tips entities.
     * -- The same goes for the doordashPay and customerTips fields.
     *    val totalEarnings: Double? = null,
     *    val doordashPay: Double? = null,
     *    val customerTips: Double? = null,
     *
     */

    /** Total number of deliveries completed in this dash (from Dash Summary).
     * -- This should be calculated from the orders associated with this dash.
     *    val deliveriesCompleted: Int? = null,
     */

    /** Total number of offers received during this dash (can be aggregated).
     * -- This should be calculated from the orders associated with this dash.
     * -- Same goes for offersAccepted.
     *    val offersReceived: Int? = null,
     *    val offersAccepted: Int? = null,
     *
     */
)