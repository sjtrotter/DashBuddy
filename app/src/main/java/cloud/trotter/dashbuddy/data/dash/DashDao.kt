package cloud.trotter.dashbuddy.data.dash

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cloud.trotter.dashbuddy.data.stats.DashStatsTuple
import cloud.trotter.dashbuddy.data.stats.OfferStatsTuple
import cloud.trotter.dashbuddy.data.stats.OrderStatsTuple
import cloud.trotter.dashbuddy.data.stats.PayStatsTuple
import kotlinx.coroutines.flow.Flow

/**
 * A collection of Room database operations for managing [DashEntity] objects.
 */
@Dao
interface DashDao {
    /**
     * Inserts a new dash into the table.
     * If there's a conflict (which shouldn't happen with autoGenerate = true for ID),
     * it will abort the transaction.
     * @param dash The [DashEntity] object to insert.
     * @return The row ID of the newly inserted dash.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDash(dash: DashEntity): Long

    /**
     * Updates an existing dash in the table.
     * @param dash The [DashEntity] object to update. It must have a valid 'id'.
     */
    @Update
    suspend fun updateDash(dash: DashEntity)

    /**
     * Retrieves a specific dash from the table by its ID.
     * @param id The ID of the dash to retrieve.
     * @return The [DashEntity] object if found, otherwise null.
     */
    @Query("SELECT * FROM dashes WHERE id = :id")
    suspend fun getDashById(id: Long): DashEntity?

    /**
     * Retrieves a specific dash from the table by its ID, as an observable Flow.
     * @param id The ID of the dash to retrieve.
     * @return A [Flow] emitting the [DashEntity] object if found, otherwise null.
     */
    @Query("SELECT * FROM dashes WHERE id = :id")
    fun getDashByIdFlow(id: Long): Flow<DashEntity?>

    /**
     * Retrieves all dashes from the table, ordered by start time in descending order (newest first).
     * Returns a Flow for reactive updates.
     * @return A [Flow] emitting a list of all [DashEntity] objects.
     */
    @Query("SELECT * FROM dashes ORDER BY startTime DESC")
    fun getAllDashesFlow(): Flow<List<DashEntity>>

    /**
     * Retrieves the most recently started dash.
     * Useful for quickly accessing the last logged dash session.
     * @return The most recent [DashEntity] object if the table is not empty, otherwise null.
     */
    @Query("SELECT * FROM dashes ORDER BY startTime DESC LIMIT 1")
    suspend fun getMostRecentDash(): DashEntity?

    /**
     * Retrieves a list of dashes within a specific date range, ordered by start time.
     * Timestamps should be in milliseconds since epoch.
     * @param startTimeRange The start of the date range (inclusive).
     * @param endTimeRange The end of the date range (inclusive).
     * @return A [Flow] emitting a list of [DashEntity] objects within the specified range.
     */
    @Query("SELECT * FROM dashes WHERE startTime >= :startTimeRange AND startTime <= :endTimeRange ORDER BY startTime DESC")
    fun getDashesByDateRangeFlow(startTimeRange: Long, endTimeRange: Long): Flow<List<DashEntity>>

    /**
     * Deletes a specific dash from the table by its ID.
     * @param id The ID of the dash to delete.
     */
    @Query("DELETE FROM dashes WHERE id = :id")
    suspend fun deleteDashById(id: Long)

    /**
     * Deletes all dashes from the table. Use with caution.
     */
    @Query("DELETE FROM dashes")
    suspend fun clearAllDashes()

    // Add this query to increment the totalDistance for a dash
    @Query("UPDATE dashes SET totalDistance = COALESCE(totalDistance, 0.0) + :mileageToAdd WHERE id = :dashId")
    suspend fun incrementDashMileage(dashId: Long, mileageToAdd: Double)

    // Add this to DashDao.kt
    @Transaction
    @Query("SELECT * FROM dashes WHERE startTime >= :start AND startTime <= :end")
    fun getDashCompositesFlow(start: Long, end: Long): Flow<List<DashComposite>>

    // --- FAST STATS QUERIES (Lightweight) ---

    @Query("SELECT id, startTime, stopTime, totalDistance FROM dashes WHERE startTime >= :start AND startTime <= :end")
    fun getDashStats(start: Long, end: Long): Flow<List<DashStatsTuple>>

    @Query("SELECT id, dashId, acceptTime, status, timestamp FROM offers WHERE timestamp >= :start AND timestamp <= :end")
    fun getOfferStats(start: Long, end: Long): Flow<List<OfferStatsTuple>>

    @Query("SELECT id, offerId, completionTimestamp, mileage FROM orders WHERE completionTimestamp >= :start AND completionTimestamp <= :end")
    fun getOrderStats(start: Long, end: Long): Flow<List<OrderStatsTuple>>

    @Query("SELECT offerId, amount FROM app_pays WHERE timestamp >= :start AND timestamp <= :end")
    fun getAppPayStats(start: Long, end: Long): Flow<List<PayStatsTuple>>

    // NOTE: We alias 'orderId' to 'offerId' so we can reuse the PayStatsTuple class
    @Query("SELECT orderId as offerId, amount FROM customer_tips WHERE timestamp >= :start AND timestamp <= :end")
    fun getTipStats(start: Long, end: Long): Flow<List<PayStatsTuple>>
}