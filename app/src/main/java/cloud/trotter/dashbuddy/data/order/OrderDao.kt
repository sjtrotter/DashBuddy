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

    /**
     * Inserts a single order into the table.
     * If there's a conflict (e.g., same primary key), it replaces the old data.
     *
     * @param order The OrderEntity to insert.
     * @return The row ID of the newly inserted order.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity): Long

    /**
     * Inserts a list of orders into the table.
     * If there's a conflict, it replaces the old data.
     *
     * @param orders The list of OrderEntities to insert.
     * @return A list of row IDs for the newly inserted orders.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrders(orders: List<OrderEntity>): List<Long>

    /**
     * Updates an existing order in the table.
     *
     * @param order The OrderEntity to update.
     */
    @Update
    suspend fun updateOrder(order: OrderEntity)

    /**
     * Deletes an order from the table.
     *
     * @param order The OrderEntity to delete.
     */
    @Delete
    suspend fun deleteOrder(order: OrderEntity)

    /**
     * Deletes all orders from the table.
     * Use with caution.
     */
    @Query("DELETE FROM orders")
    suspend fun deleteAllOrders()

    /**
     * Retrieves a specific order by its ID.
     *
     * @param orderId The ID of the order to retrieve.
     * @return The OrderEntity if found, otherwise null.
     */
    @Query("SELECT * FROM orders WHERE id = :orderId")
    suspend fun getOrderById(orderId: Long): OrderEntity?

    /**
     * Retrieves all orders associated with a specific offer ID.
     * Returns a Flow for observable updates.
     *
     * @param offerId The ID of the offer.
     * @return A Flow emitting a list of OrderEntities.
     */
    @Query("SELECT * FROM orders WHERE offerId = :offerId ORDER BY orderIndex ASC")
    fun getOrdersForOffer(offerId: Long): Flow<List<OrderEntity>>

    /**
     * Retrieves all orders associated with a specific offer ID as a simple list (non-observable).
     *
     * @param offerId The ID of the offer.
     * @return A list of OrderEntities.
     */
    @Query("SELECT * FROM orders WHERE offerId = :offerId ORDER BY orderIndex ASC")
    suspend fun getOrdersForOfferList(offerId: Long): List<OrderEntity>


    /**
     * Retrieves all orders from the table.
     * Returns a Flow for observable updates.
     *
     * @return A Flow emitting a list of all OrderEntities.
     */
    @Query("SELECT * FROM orders ORDER BY offerId ASC, orderIndex ASC")
    fun getAllOrders(): Flow<List<OrderEntity>>

    /**
     * Retrieves all orders as a simple list (non-observable).
     *
     * @return A list of all OrderEntities.
     */
    @Query("SELECT * FROM orders ORDER BY offerId ASC, orderIndex ASC")
    suspend fun getAllOrdersList(): List<OrderEntity>

}
