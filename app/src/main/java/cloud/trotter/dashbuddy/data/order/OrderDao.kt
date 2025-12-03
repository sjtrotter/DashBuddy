package cloud.trotter.dashbuddy.data.order

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the [OrderEntity].
 */
@Dao
interface OrderDao {

    // --- Basic CUD (Create, Update, Delete) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrders(orders: List<OrderEntity>): List<Long>

    @Update
    suspend fun updateOrder(order: OrderEntity)

    @Delete
    suspend fun deleteOrder(order: OrderEntity)

    @Query("DELETE FROM orders")
    suspend fun deleteAllOrders()

    // --- Basic Queries ---

    @Query("SELECT * FROM orders WHERE id = :orderId")
    suspend fun getOrderById(orderId: Long): OrderEntity?

    @Query("SELECT * FROM orders WHERE offerId = :offerId ORDER BY orderIndex ASC")
    fun getOrdersForOffer(offerId: Long): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE offerId = :offerId ORDER BY orderIndex ASC")
    suspend fun getOrdersForOfferList(offerId: Long): List<OrderEntity>

    /**
     * Retrieves all orders associated with a given list of offer IDs.
     * This is useful for fetching all related orders for a set of dashes at once.
     * @param offerIds The list of offer IDs to fetch orders for.
     * @return A Flow emitting a list of matching OrderEntities.
     */
    @Query("SELECT * FROM orders WHERE offerId IN (:offerIds)")
    fun getOrdersForOffersFlow(offerIds: List<Long>): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders ORDER BY offerId ASC, orderIndex ASC")
    fun getAllOrders(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders ORDER BY offerId ASC, orderIndex ASC")
    suspend fun getAllOrdersList(): List<OrderEntity>

    // --- New, More Specific Queries & Updates ---

    /**
     * Updates the status of a specific order.
     *
     * @param orderId The ID of the order to update.
     * @param newStatus The new status string (e.g., "AT_STORE", "COMPLETED").
     */
    @Query("UPDATE orders SET status = :newStatus WHERE id = :orderId")
    suspend fun updateOrderStatus(orderId: Long, newStatus: OrderStatus)

    /**
     * Updates the storeId for a specific order.
     * Useful for your deferred store linking strategy.
     *
     * @param orderId The ID of the order to update.
     * @param specificStoreId The ID of the specific, identified StoreEntity.
     */
    @Query("UPDATE orders SET storeId = :specificStoreId WHERE id = :orderId")
    suspend fun linkOrderToStore(orderId: Long, specificStoreId: Long)

    /**
     * Updates the customerId for a specific order.
     *
     * @param orderId The ID of the order to update.
     * @param specificCustomerId The ID of the specific, identified CustomerEntity.
     */
    @Query("UPDATE orders SET customerId = :specificCustomerId WHERE id = :orderId")
    suspend fun linkOrderToCustomer(orderId: Long, specificCustomerId: Long)

    /**
     * Updates all delivery-related fields for an order upon completion.
     *
     * @param orderId The ID of the order to update.
     * @param completionTimestamp The timestamp of completion.
     */
    @Query(
        """
        UPDATE orders 
        SET 
            completionTimestamp = :completionTimestamp 
        WHERE id = :orderId
    """
    )
    suspend fun updateCompletionTimestamp(orderId: Long, completionTimestamp: Long)

    /**
     * Retrieves all "active" orders for a given dash that are not yet completed.
     * This is useful for knowing what tasks are currently in progress.
     *
     * @param dashId The ID of the current dash session.
     * @return A list of active OrderEntities.
     */
    @Query(
        """
        SELECT * FROM orders 
        WHERE offerId IN (SELECT id FROM offers WHERE dashId = :dashId) 
        AND status != 'COMPLETED'
    """
    )
    suspend fun getActiveOrdersForDash(dashId: Long): List<OrderEntity>

    /**
     * Retrieves all orders for a given dash, regardless of status.
     * Returns an observable Flow.
     *
     * @param dashId The ID of the current dash session.
     * @return A Flow emitting a list of all OrderEntities for the dash.
     */
    @Query(
        """
        SELECT * FROM orders 
        WHERE offerId IN (SELECT id FROM offers WHERE dashId = :dashId)
        ORDER BY orderIndex ASC
    """
    )
    fun getAllOrdersForDash(dashId: Long): Flow<List<OrderEntity>>

    @Query("UPDATE orders SET customerNameHash = :customerNameHash WHERE id = :orderId")
    suspend fun setCustomerNameHash(orderId: Long, customerNameHash: String)

    @Query("SELECT SUM(mileage) FROM orders WHERE offerId IN (:offerIds)")
    fun getTotalActiveMilesForOffersFlow(offerIds: List<Long>): Flow<Double?>

}
