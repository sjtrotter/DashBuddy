package cloud.trotter.dashbuddy.data.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StoreRepo(private val storeDao: StoreDao) {

    /**
     * Upserts a store into the database.
     * @param store The StoreEntity to upsert.
     * @return The ID of the upserted store.
     */
    suspend fun upsertStore(store: StoreEntity): Long {
        return withContext(Dispatchers.IO) {
            storeDao.upsertStore(store)
        }
    }

    /**
     * Finds a store by its unique name and address combination.
     * @param name The name of the store.
     * @param address The address of the store.
     * @return A nullable StoreEntity if found.
     */
    suspend fun getStoreByNameAndAddress(name: String, address: String): StoreEntity? {
        return withContext(Dispatchers.IO) {
            storeDao.getStoreByNameAndAddress(name, address)
        }
    }

    suspend fun getStoresByAddress(address: String): List<StoreEntity> {
        return withContext(Dispatchers.IO) {
            storeDao.getStoresByAddress(address)
        }
    }
}
