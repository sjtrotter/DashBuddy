package cloud.trotter.dashbuddy.data.pay

import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AppPayRepo(private val appPayDao: AppPayDao) {
    /**
     * Retrieves all [AppPayEntity]s, ordered by start time descending, as an observable Flow.
     */
    val allAppPays: Flow<List<AppPayEntity>> = appPayDao.getAllAppPays()

    /**
     * Retrieves an AppPayType by name. If it doesn't exist, it creates it.
     * This ensures you always get a valid ID for a given pay type name.
     * This operation is transactional to prevent race conditions.
     *
     * @param name The name of the pay type (e.g., "Base Pay").
     * @return The Long ID of the existing or newly created AppPayType.
     */
    @Transaction
    suspend fun upsertPayType(name: String): Long {
        return withContext(Dispatchers.IO) {
            val existingType = appPayDao.getPayTypeByName(name)
            existingType?.id ?: appPayDao.insertPayType(AppPayType(name = name))
        }
    }

    suspend fun insert(appPay: AppPayEntity): Long {
        return withContext(Dispatchers.IO) {
            appPayDao.insert(appPay)
        }
    }

    suspend fun insertAll(appPays: List<AppPayEntity>): List<Long> {
        return withContext(Dispatchers.IO) {
            appPayDao.insertAll(appPays)
        }
    }

    fun getPayComponentsForOffer(offerId: Long): Flow<List<AppPayEntity>> {
        return appPayDao.getPayComponentsForOffer(offerId)
    }

    suspend fun getPayComponentsForOfferList(offerId: Long): List<AppPayEntity> {
        return withContext(Dispatchers.IO) {
            appPayDao.getPayComponentsForOfferList(offerId)
        }
    }

    suspend fun getPayTypeById(id: Long): AppPayType? {
        return withContext(Dispatchers.IO) {
            appPayDao.getPayTypeById(id)
        }
    }
}
