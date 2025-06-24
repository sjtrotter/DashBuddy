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

    // --- Basic CUD (Create, Update, Delete) ---

    suspend fun insertOrder(order: OrderEntity): Long {
        return withContext(Dispatchers.IO) {
            orderDao.insertOrder(order)
        }
    }

    suspend fun insertOrders(orders: List<OrderEntity>): List<Long> {
        return withContext(Dispatchers.IO) {
            orderDao.insertOrders(orders)
        }
    }

    suspend fun updateOrder(order: OrderEntity) {
        withContext(Dispatchers.IO) {
            orderDao.updateOrder(order)
        }
    }

    suspend fun deleteOrder(order: OrderEntity) {
        withContext(Dispatchers.IO) {
            orderDao.deleteOrder(order)
        }
    }

    suspend fun deleteAllOrders() {
        withContext(Dispatchers.IO) {
            orderDao.deleteAllOrders()
        }
    }

    // --- Basic Queries ---

    suspend fun getOrderById(orderId: Long): OrderEntity? {
        return withContext(Dispatchers.IO) {
            orderDao.getOrderById(orderId)
        }
    }

    fun getOrdersForOffer(offerId: Long): Flow<List<OrderEntity>> {
        return orderDao.getOrdersForOffer(offerId)
    }

    suspend fun getOrdersForOfferList(offerId: Long): List<OrderEntity> {
        return withContext(Dispatchers.IO) {
            orderDao.getOrdersForOfferList(offerId)
        }
    }

    fun getAllOrders(): Flow<List<OrderEntity>> {
        return orderDao.getAllOrders()
    }

    suspend fun getAllOrdersList(): List<OrderEntity> {
        return withContext(Dispatchers.IO) {
            orderDao.getAllOrdersList()
        }
    }

    // --- New, More Specific Methods from Updated DAO ---

    /**
     * Updates the status of a specific order.
     */
    suspend fun updateOrderStatus(orderId: Long, newStatus: OrderStatus) {
        withContext(Dispatchers.IO) {
            orderDao.updateOrderStatus(orderId, newStatus)
        }
    }

    /**
     * Updates the storeId for a specific order.
     */
    suspend fun linkOrderToStore(orderId: Long, specificStoreId: Long) {
        withContext(Dispatchers.IO) {
            orderDao.linkOrderToStore(orderId, specificStoreId)
        }
    }

    /**
     * Updates the customerId for a specific order.
     */
    suspend fun linkOrderToCustomer(orderId: Long, specificCustomerId: Long) {
        withContext(Dispatchers.IO) {
            orderDao.linkOrderToCustomer(orderId, specificCustomerId)
        }
    }

    /**
     * Updates all delivery-related fields for an order upon completion.
     */
    suspend fun markOrderAsCompleted(orderId: Long, customerId: Long?, completionTimestamp: Long) {
        withContext(Dispatchers.IO) {
            orderDao.markOrderAsCompleted(orderId, customerId, completionTimestamp)
        }
    }

    /**
     * Retrieves all "active" orders for a given dash that are not yet completed.
     */
    suspend fun getActiveOrdersForDash(dashId: Long): List<OrderEntity> {
        return withContext(Dispatchers.IO) {
            orderDao.getActiveOrdersForDash(dashId)
        }
    }

    /**
     * Retrieves all orders for a given dash, regardless of status, as an observable Flow.
     */
    fun getAllOrdersForDash(dashId: Long): Flow<List<OrderEntity>> {
        return orderDao.getAllOrdersForDash(dashId)
    }

    suspend fun setCustomerNameHash(orderId: Long, customerNameHash: String) {
        withContext(Dispatchers.IO) {
            orderDao.setCustomerNameHash(orderId, customerNameHash)
        }
    }
}
