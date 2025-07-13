package cloud.trotter.dashbuddy.data.zone

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A collection of Room database operations for managing [ZoneEntity] objects.
 */
@Dao
interface ZoneDao {
    /**
     * Inserts a new zone into the table.
     * If a zone with the same zoneName already exists (due to the unique constraint),
     * the insert will be ignored, and this method will return -1.
     * @param zone The [ZoneEntity] object to insert.
     * @return The row ID of the newly inserted zone, or -1 if the insert was ignored due to a conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(zone: ZoneEntity): Long

    /**
     * Retrieves a zone from the table by its name.
     * Since zoneName is unique, this will return at most one zone.
     * @param name The name of the zone to retrieve.
     * @return The [ZoneEntity] object if found, otherwise null.
     */
    @Query("SELECT * FROM zones WHERE zoneName = :name LIMIT 1")
    suspend fun getZoneByName(name: String): ZoneEntity?

    /**
     * Retrieves a zone from the table by its ID.
     * @param id The ID of the zone to retrieve.
     * @return The [ZoneEntity] object if found, otherwise null.
     */
    @Query("SELECT * FROM zones WHERE id = :id LIMIT 1")
    suspend fun getZoneById(id: Long): ZoneEntity?

    /**
     * Retrieves all zones from the table, ordered by name.
     * Returns a Flow for reactive updates.
     * @return A [Flow] emitting a list of all [ZoneEntity] objects.
     */
    @Query("SELECT * FROM zones ORDER BY zoneName ASC")
    fun getAllZones(): Flow<List<ZoneEntity>>

    // Add this function to get all relevant zones at once.
    @Query("SELECT * FROM zones WHERE id IN (:zoneIds)")
    fun getZonesByIdsFlow(zoneIds: List<Long>): Flow<List<ZoneEntity>>
}