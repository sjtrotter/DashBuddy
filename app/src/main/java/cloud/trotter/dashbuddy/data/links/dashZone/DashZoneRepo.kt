package cloud.trotter.dashbuddy.data.links.dashZone

import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.zone.ZoneEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import cloud.trotter.dashbuddy.log.Logger as Log

/** A repository class containing methods to interact with the Data Access Object (DAO)
 * [DashZoneDao] for managing [DashZoneEntity] objects in the database, linking dashes to zones.
 */
class DashZoneRepo(private val dashZoneDao: DashZoneDao) {

    private val tag = "DashZoneRepository"

    /**
     * Links a dash to a zone.
     * Ensures the operation runs on the IO dispatcher.
     * @param dashId The ID of the dash.
     * @param zoneId The ID of the zone.
     * @param isStartZone Whether this is the starting zone for the dash.
     * @param enteredAtMillis Timestamp when this zone was entered for this dash.
     * @return The row ID of the newly inserted link, or -1 if there was a conflict.
     */
    suspend fun linkDashToZone(
        dashId: Long,
        zoneId: Long,
        isStartZone: Boolean,
        enteredAtMillis: Long = System.currentTimeMillis()
    ) {
        withContext(Dispatchers.IO) {
            val dashZoneLink = DashZoneEntity(
                dashId = dashId,
                zoneId = zoneId,
                isStartZone = isStartZone,
                enteredZoneAtMillis = enteredAtMillis
            )
            Log.d(tag, "Linking Dash ID $dashId to Zone ID $zoneId. Is Start: $isStartZone")
            dashZoneDao.linkDashToZone(dashZoneLink)
        }
    }

    /**
     * Retrieves all zones associated with a specific dash, ordered by when the zone was entered.
     * @param dashId The ID of the dash.
     * @return A [Flow] emitting a list of [ZoneEntity] objects.
     */
    fun getZonesForDashFlow(dashId: Long): Flow<List<ZoneEntity>> {
        Log.d(tag, "Getting zones flow for Dash ID: $dashId")
        return dashZoneDao.getZonesForDashFlow(dashId)
    }

    /**
     * Retrieves all dashes that have operated in a specific zone,
     * ordered by the dash start time in descending order.
     * @param zoneId The ID of the zone.
     * @return A [Flow] emitting a list of [DashEntity] objects.
     * @see DashZoneDao.getDashesInZoneFlow
     */
    fun getDashesInZoneFlow(zoneId: Long): Flow<List<DashEntity>> {
        Log.d(tag, "Getting dashes flow for Zone ID: $zoneId")
        return dashZoneDao.getDashesInZoneFlow(zoneId)
    }

    /**
     * Retrieves all DashZoneEntity links for a specific dash, ordered by when the zone was entered.
     * @param dashId The ID of the dash.
     * @return A [Flow] emitting a list of [DashZoneEntity] objects.
     * @see DashZoneDao.getZoneLinksForDashFlow
     */
    fun getZoneLinksForDashFlow(dashId: Long): Flow<List<DashZoneEntity>> {
        Log.d(tag, "Getting zone links flow for Dash ID: $dashId")
        return dashZoneDao.getZoneLinksForDashFlow(dashId)
    }

    /**
     * Retrieves a specific DashZoneEntity link.
     * Ensures the operation runs on the IO dispatcher.
     * @param dashId The ID of the dash.
     * @param zoneId The ID of the zone.
     * @return The [DashZoneEntity] if the link exists, otherwise null.
     * @see DashZoneDao.getSpecificDashZoneLink
     */
    suspend fun getSpecificDashZoneLink(dashId: Long, zoneId: Long): DashZoneEntity? {
        return withContext(Dispatchers.IO) {
            Log.d(tag, "Getting specific link for Dash ID $dashId and Zone ID $zoneId")
            dashZoneDao.getSpecificDashZoneLink(dashId, zoneId)
        }
    }

    /**
     * Deletes all zone links for a specific dash.
     * Ensures the operation runs on the IO dispatcher.
     * @param dashId The ID of the dash whose zone links should be removed.
     * @return The number of rows deleted.
     * @see DashZoneDao.deleteZoneLinksForDash
     */
    suspend fun deleteZoneLinksForDash(dashId: Long) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Deleting zone links for Dash ID: $dashId")
            dashZoneDao.deleteZoneLinksForDash(dashId)
        }
    }
}