package cloud.trotter.dashbuddy.data.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Represents a store entity in the database.
 * Tracks the store's [name] and [address], if known, with a unique [id].
 * May at later date add properties for lat/long, rating (by dashers) and more.
 */
@Entity(
    tableName = "stores",
    indices = [Index(value = ["name", "address"], unique = true)]
)
data class StoreEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val address: String? = null,
    // latitude, longitude, rating, type, chain?
)