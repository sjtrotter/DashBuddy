package cloud.trotter.dashbuddy.data.customer

import cloud.trotter.dashbuddy.log.Logger as Log

/**
 * Repository for managing customer data.
 * This class abstracts the data source (the Room DAO) from the rest of the application.
 *
 * @property customerDao The Data Access Object for the customers table.
 */
class CustomerRepo(private val customerDao: CustomerDao) {

    private val tag = "CustomerRepository"

    /**
     * Safely gets the ID of an existing customer or inserts a new one if they don't exist.
     * This is the primary method for creating or finding customers. It delegates the complex
     * transactional logic to the DAO.
     *
     * @param nameHash The hash of the customer's name.
     * @param addressHash The hash of the customer's address.
     * @return The database ID (Long) of the existing or newly created customer.
     */
    suspend fun getOrInsert(nameHash: String, addressHash: String): Long {
        return try {
            Log.d(tag, "Attempting to get or insert customer.")
            customerDao.getOrInsert(nameHash, addressHash)
        } catch (e: Exception) {
            Log.e(tag, "!!! Error during getOrInsert for customer. !!!", e)
            -1L
        }
    }

    /**
     * Retrieves a single customer by their unique ID.
     *
     * @param id The ID of the customer to retrieve.
     * @return A CustomerEntity if found, otherwise null.
     */
    suspend fun getCustomerById(id: Long): CustomerEntity? {
        return try {
            customerDao.getById(id)
        } catch (e: Exception) {
            Log.e(tag, "!!! Error fetching customer by ID: $id !!!", e)
            null
        }
    }

    // You can add other methods here as needed, for example:
    // suspend fun updateCustomer(customer: CustomerEntity) { ... }
    // suspend fun deleteCustomer(customer: CustomerEntity) { ... }
}