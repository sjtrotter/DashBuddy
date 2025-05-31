package cloud.trotter.dashbuddy.data.links.dashZone

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.zone.ZoneEntity
import kotlinx.coroutines.flow.Flow

/**
 * A collection of Room database operations for managing [DashZoneEntity] objects.
 */
@Dao
interface DashZoneDao {
    /**
     * Links a dash to a zone.
     * If the link (same dashId and zoneId) already exists, it will be ignored.
     * This is useful if you might try to log entering the same zone multiple times
     * for the same dash but only want one distinct link entry per dash-zone pair.
     * @param dashZone The [DashZoneEntity] link to insert.
     * @return The row ID of the newly inserted link, or -1 if there was a conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkDashToZone(dashZone: DashZoneEntity)

    /**
     * Retrieves all zones associated with a specific dash, ordered by when the zone was entered.
     * @param dashId The ID of the dash.
     * @return A [Flow] emitting a list of [ZoneEntity] objects.
     */
    @Query(
        """  SELECT Z.* FROM zones AS Z 
                INNER JOIN dash_zone_link AS DZL
                ON Z.id = DZL.zoneId
                WHERE DZL.dashId = :dashId
                ORDER BY DZL.enteredZoneAtMillis ASC """
    )
    fun getZonesForDashFlow(dashId: Long): Flow<List<ZoneEntity>>

    /**
     * Retrieves all dashes that have operated in a specific zone,
     * ordered by the dash start time in descending order (newest dashes first).
     * @param zoneId The ID of the zone.
     * @return A [Flow] emitting a list of [DashEntity] objects.
     */
    @Query(
        """  SELECT D.* FROM dashes AS D 
                INNER JOIN dash_zone_link AS DZL
                ON D.id = DZL.dashId
                WHERE DZL.zoneId = :zoneId
                ORDER BY D.startTime DESC """
    )
    fun getDashesInZoneFlow(zoneId: Long): Flow<List<DashEntity>>

    /**
     * Retrieves all DashZoneEntity links for a specific dash, ordered by when the zone was entered.
     * This can be useful if you need the isStartZone or enteredZoneAtMillis information.
     * @param dashId The ID of the dash.
     * @return A [Flow] emitting a list of [DashZoneEntity] objects.
     */
    @Query("SELECT * FROM dash_zone_link WHERE dashId = :dashId ORDER BY enteredZoneAtMillis ASC")
    fun getZoneLinksForDashFlow(dashId: Long): Flow<List<DashZoneEntity>>

    /**
     * Retrieves a specific DashZoneEntity link.
     * @param dashId The ID of the dash.
     * @param zoneId The ID of the zone.
     * @return The [DashZoneEntity] if the link exists, otherwise null.
     */
    @Query("SELECT * FROM dash_zone_link WHERE dashId = :dashId AND zoneId = :zoneId LIMIT 1")
    suspend fun getSpecificDashZoneLink(dashId: Long, zoneId: Long): DashZoneEntity?

    /**
     * Deletes all zone links for a specific dash.
     * Useful if a dash record is deleted and you want to clean up its associations.
     * (Though ForeignKey onDelete = CASCADE on DashZoneEntity for dashId would also handle this).
     * @param dashId The ID of the dash whose zone links should be removed.
     * @return The number of rows deleted.
     */
    @Query("DELETE FROM dash_zone_link WHERE dashId = :dashId")
    suspend fun deleteZoneLinksForDash(dashId: Long)
}