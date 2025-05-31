package cloud.trotter.dashbuddy.data.zone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * A repository class containing methods to interact with the Data Access Object (DAO)
 * [ZoneDao] for managing [ZoneEntity] objects in the database.
 */
class ZoneRepo(private val zoneDao: ZoneDao) {

    /**
     * Retrieves all zones from the database, ordered by name.
     * Returns a Flow for reactive updates.
     */
    val allZones: Flow<List<ZoneEntity>> = zoneDao.getAllZones()

    /**
     * Gets a zone by its name, or inserts it if it doesn't exist.
     * Returns the ID of the existing or newly inserted zone.
     */
    suspend fun getOrInsertZone(zoneName: String): Long {
        return withContext(Dispatchers.IO) {
            val existingZone = zoneDao.getZoneByName(zoneName)
            if (existingZone != null) {
                existingZone.id
            } else {
                val newZoneId = zoneDao.insert(ZoneEntity(zoneName = zoneName))
                newZoneId
            }
        }
    }

    suspend fun getZoneById(id: Long): ZoneEntity? {
        return zoneDao.getZoneById(id)
    }
}
