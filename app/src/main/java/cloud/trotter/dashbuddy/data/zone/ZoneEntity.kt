package cloud.trotter.dashbuddy.data.zone

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Represents a Doordash Zone in the database.
 * @property id The unique identifier for the zone.
 * @property zoneName The name of the zone.
 */
@Entity(
    tableName = "zones",
    indices = [Index(value = ["zoneName"], unique = true)]
)
data class ZoneEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    val zoneName: String = "",
)