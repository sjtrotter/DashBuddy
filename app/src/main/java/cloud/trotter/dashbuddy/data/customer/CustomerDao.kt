package cloud.trotter.dashbuddy.data.customer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * Data Access Object (DAO) for the CustomerEntity.
 * This interface defines the database interactions for the 'customers' table.
 */
@Dao
interface CustomerDao {

    /**
     * Inserts a new customer into the database.
     * If a customer with the same nameHash and addressHash already exists, the insert will be ignored.
     *
     * @param customer The CustomerEntity to insert.
     * @return The row ID of the newly inserted customer, or -1 if the insertion was ignored due to a conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(customer: CustomerEntity): Long

    /**
     * Retrieves a customer by their unique ID.
     *
     * @param id The primary key of the customer.
     * @return The matching CustomerEntity, or null if no customer is found.
     */
    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun getById(id: Long): CustomerEntity?

    /**
     * Updates an existing customer in the database.
     *
     * @param customer The CustomerEntity to update.
     */
    @Update
    suspend fun update(customer: CustomerEntity)

    /**
     * Retrieves a customer by the unique combination of their name and address hashes.
     *
     * @param nameHash The hash of the customer's name.
     * @param addressHash The hash of the customer's address.
     * @return The matching CustomerEntity, or null if no customer is found.
     */
    @Query("SELECT * FROM customers WHERE nameHash = :nameHash AND addressHash = :addressHash")
    suspend fun getByHashes(nameHash: String, addressHash: String): CustomerEntity?

    /**
     * A transactional function that safely gets the ID of an existing customer or inserts a new one
     * if they don't exist, and then returns their ID. This is the recommended method for creating customers.
     * The @Transaction annotation ensures this operation is performed atomically.
     *
     * @param nameHash The hash of the customer's name.
     * @param addressHash The hash of the customer's address.
     * @return The database ID (Long) of the existing or newly created customer.
     */
    @Transaction
    suspend fun getOrInsert(nameHash: String, addressHash: String): Long {
        val existingCustomer = getByHashes(nameHash, addressHash)

        if (existingCustomer != null) {
            // IF CUSTOMER EXISTS: Update their lastSeen timestamp and return their ID.
            val updatedCustomer = existingCustomer.copy(lastSeen = System.currentTimeMillis())
            update(updatedCustomer)
            return existingCustomer.id
        } else {
            // IF CUSTOMER DOES NOT EXIST: Create a new one and insert it.
            val newCustomer = CustomerEntity(
                nameHash = nameHash,
                addressHash = addressHash,
                lastSeen = System.currentTimeMillis()
            )
            return insert(newCustomer)
        }
    }
}