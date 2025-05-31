package cloud.trotter.dashbuddy.data.dash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import cloud.trotter.dashbuddy.log.Logger as Log

/**
 * A repository class containing methods to interact with the Data Access Object (DAO)
 * [DashDao] for managing [DashEntity] objects in the database.
 */
class DashRepo(private val dashDao: DashDao) {
    private val tag = "DashRepository"

    /**
     * Retrieves all [DashEntity]s, ordered by start time descending, as an observable Flow.
     */
    val allDashes: Flow<List<DashEntity>> = dashDao.getAllDashesFlow()

    /**
     * Retrieves a specific dash by its ID, as an observable Flow.
     * @param id The ID of the dash.
     * @return A [Flow] emitting the [DashEntity] if found, otherwise null.
     */
    fun getDashByIdFlow(id: Long): Flow<DashEntity?> {
        return dashDao.getDashByIdFlow(id)
    }

    /**
     * Retrieves dashes within a specific date range, ordered by start time
     * descending, as an observable Flow.
     * @param startTimeRange The start of the date range (inclusive).
     * @param endTimeRange The end of the date range (inclusive).
     * @return A [Flow] emitting a list of [DashEntity] objects within the specified range.
     */
    fun getDashesByDateRangeFlow(startTimeRange: Long, endTimeRange: Long): Flow<List<DashEntity>> {
        return dashDao.getDashesByDateRangeFlow(startTimeRange, endTimeRange)
    }

    /**
     * Inserts a new dash. Ensures the operation runs on the IO dispatcher.
     * @param dash The [DashEntity] to insert.
     * @return The ID of the newly inserted dash.
     */
    suspend fun insertDash(dash: DashEntity): Long {
        return withContext(Dispatchers.IO) {
            Log.d(tag, "Inserting new dash: StartTime = ${dash.startTime}")
            dashDao.insertDash(dash)
        }
    }

    /**
     * Updates an existing dash. Ensures the operation runs on the IO dispatcher.
     * @param dash The [DashEntity] to update.
     */
    suspend fun updateDash(dash: DashEntity) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Updating dash with ID: ${dash.id}")
            dashDao.updateDash(dash)
        }
    }

    /**
     * Retrieves a specific dash by its ID. Ensures the operation runs on the IO dispatcher.
     * @param id The ID of the dash.
     * @return The [DashEntity] if found, otherwise null.
     */
    suspend fun getDashById(id: Long): DashEntity? {
        return withContext(Dispatchers.IO) {
            Log.d(tag, "Getting dash by ID: $id")
            dashDao.getDashById(id)
        }
    }

    /**
     * Retrieves the most recently started dash. Ensures the operation runs on the IO dispatcher.
     * @return The most recent [DashEntity] or null if none exist.
     */
    suspend fun getMostRecentDash(): DashEntity? {
        return withContext(Dispatchers.IO) {
            Log.d(tag, "Getting most recent dash.")
            dashDao.getMostRecentDash()
        }
    }

    /**
     * Deletes a specific dash by its ID. Ensures the operation runs on the IO dispatcher.
     * @param id The ID of the dash to delete.
     */
    suspend fun deleteDashById(id: Long) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Deleting dash by ID: $id")
            dashDao.deleteDashById(id)
        }
    }

    /**
     * Clears all dashes from the database. Ensures the operation runs on the IO dispatcher.
     * Use with caution.
     */
    suspend fun clearAllDashes() {
        withContext(Dispatchers.IO) {
            Log.w(tag, "Clearing ALL dashes from the database. --(not actually set up)")
        } // Use warn for destructive actions dashDao.clearAllDashes() } }
    }
}