package cloud.trotter.dashbuddy.data.order // Or your specific repository package

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository for managing OrderEntity data.
 * It abstracts the data source (OrderDao) from the rest of the application.
 *
 * @property orderDao The Data Access Object for orders.
 */
class OrderRepo(private val orderDao: OrderDao) {

    /**
     * Inserts a single order into the database.
     *
     * @param order The OrderEntity to insert.
     * @return The row ID of the newly inserted order.
     */
    suspend fun insertOrder(order: OrderEntity): Long {
        return withContext(Dispatchers.IO) {
            orderDao.insertOrder(order)
        }
    }

    /**
     * Inserts a list of orders into the database.
     *
     * @param orders The list of OrderEntities to insert.
     * @return A list of row IDs for the newly inserted orders.
     */
    suspend fun insertOrders(orders: List<OrderEntity>): List<Long> {
        return withContext(Dispatchers.IO) {
            orderDao.insertOrders(orders)
        }
    }

    /**
     * Updates an existing order in the database.
     *
     * @param order The OrderEntity to update.
     */
    suspend fun updateOrder(order: OrderEntity) {
        withContext(Dispatchers.IO) {
            orderDao.updateOrder(order)
        }
    }

    /**
     * Deletes an order from the database.
     *
     * @param order The OrderEntity to delete.
     */
    suspend fun deleteOrder(order: OrderEntity) {
        withContext(Dispatchers.IO) {
            orderDao.deleteOrder(order)
        }
    }

    /**
     * Deletes all orders from the database.
     */
    suspend fun deleteAllOrders() {
        withContext(Dispatchers.IO) {
            orderDao.deleteAllOrders()
        }
    }

    /**
     * Retrieves a specific order by its ID.
     *
     * @param orderId The ID of the order.
     * @return The OrderEntity if found, otherwise null.
     */
    suspend fun getOrderById(orderId: Long): OrderEntity? {
        return withContext(Dispatchers.IO) {
            orderDao.getOrderById(orderId)
        }
    }

    /**
     * Retrieves all orders associated with a specific offer ID as an observable Flow.
     * Orders are typically ordered by their `orderIndex`.
     *
     * @param offerId The ID of the offer.
     * @return A Flow emitting a list of OrderEntities.
     */
    fun getOrdersForOffer(offerId: Long): Flow<List<OrderEntity>> {
        // Flow queries from Room are already main-safe and run on a background thread.
        return orderDao.getOrdersForOffer(offerId)
    }

    /**
     * Retrieves all orders associated with a specific offer ID as a simple list (non-observable).
     *
     * @param offerId The ID of the offer.
     * @return A list of OrderEntities.
     */
    suspend fun getOrdersForOfferList(offerId: Long): List<OrderEntity> {
        return withContext(Dispatchers.IO) {
            orderDao.getOrdersForOfferList(offerId)
        }
    }

    /**
     * Retrieves all orders as an observable Flow.
     *
     * @return A Flow emitting a list of all OrderEntities.
     */
    fun getAllOrders(): Flow<List<OrderEntity>> {
        return orderDao.getAllOrders()
    }

    /**
     * Retrieves all orders as a List.
     *
     * @return A list of all OrderEntities.
     */
    suspend fun getAllOrdersList(): List<OrderEntity> {
        return withContext(Dispatchers.IO) {
            orderDao.getAllOrdersList()
        }
    }
}
