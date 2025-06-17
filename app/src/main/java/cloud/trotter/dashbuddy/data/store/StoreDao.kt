package cloud.trotter.dashbuddy.data.store

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface StoreDao {
    /**
     * Inserts a store if it's new (based on the unique index of name+address) or
     * updates its lastSeen timestamp if it already exists.
     * @return The row ID of the inserted or updated store.
     */
    @Upsert
    suspend fun upsertStore(store: StoreEntity): Long

    /**
     * Retrieves a store by its name and address.
     * @return A nullable StoreEntity.
     */
    @Query("SELECT * FROM stores WHERE storeName = :name AND address = :address LIMIT 1")
    suspend fun getStoreByNameAndAddress(name: String, address: String): StoreEntity?

    @Query("SELECT * FROM stores WHERE address = :address")
    suspend fun getStoresByAddress(address: String): List<StoreEntity>
}
