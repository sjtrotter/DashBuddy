package cloud.trotter.dashbuddy.data.links.dashZone

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.zone.ZoneEntity
import java.util.Date

/**
 * Cross-reference table to link [dashId]s of [DashEntity]s to
 * [zoneId]s of all the [ZoneEntity]s they operated in.
 * Records the entry time as [enteredZoneAtMillis]
 * and if it was the starting zone as [isStartZone].
 * This enables a many-to-many relationship: a Dash can span multiple Zones,
 * and a Zone can have multiple Dashes.
 */
@Entity(
    tableName = "dash_zone_link",
    primaryKeys = ["dashId", "zoneId"], // Composite primary key
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
            onDelete = ForeignKey.CASCADE // Or RESTRICT if you don't want zones deleted if referenced
        )
    ],
    indices = [Index(value = ["dashId"]), Index(value = ["zoneId"])]
)
data class DashZoneEntity(
    val dashId: Long,
    val zoneId: Long,
    /** Timestamp when the dasher started operating in this specific zone during this dash. */
    val enteredZoneAtMillis: Long = Date().time, // Default to now when created
    /** Optional: Flag to indicate if this was the primary/starting zone for the dash. */
    val isStartZone: Boolean = false
)
