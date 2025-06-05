package cloud.trotter.dashbuddy.data.offer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository for managing OfferEntity data.
 * It abstracts the data source (OfferDao) from the rest of the application.
 *
 * @property offerDao The Data Access Object for offers.
 */
class OfferRepo(private val offerDao: OfferDao) {

    /**
     * Inserts a single offer into the database.
     *
     * @param offer The OfferEntity to insert.
     * @return The row ID of the newly inserted offer.
     */
    suspend fun insertOffer(offer: OfferEntity): Long {
        return withContext(Dispatchers.IO) {
            offerDao.insertOffer(offer)
        }
    }

    /**
     * Inserts a list of offers into the database.
     *
     * @param offers The list of OfferEntities to insert.
     * @return A list of row IDs for the newly inserted offers.
     */
    suspend fun insertOffers(offers: List<OfferEntity>): List<Long> {
        return withContext(Dispatchers.IO) {
            offerDao.insertOffers(offers)
        }
    }

    /**
     * Updates an existing offer in the database.
     *
     * @param offer The OfferEntity to update.
     */
    suspend fun updateOffer(offer: OfferEntity) {
        withContext(Dispatchers.IO) {
            offerDao.updateOffer(offer)
        }
    }

    /**
     * Deletes an offer from the database.
     *
     * @param offer The OfferEntity to delete.
     */
    suspend fun deleteOffer(offer: OfferEntity) {
        withContext(Dispatchers.IO) {
            offerDao.deleteOffer(offer)
        }
    }

    /**
     * Deletes all offers from the database.
     */
    suspend fun deleteAllOffers() {
        withContext(Dispatchers.IO) {
            offerDao.deleteAllOffers()
        }
    }

    /**
     * Retrieves a specific offer by its ID.
     *
     * @param offerId The ID of the offer.
     * @return The OfferEntity if found, otherwise null.
     */
    suspend fun getOfferById(offerId: Long): OfferEntity? {
        return withContext(Dispatchers.IO) {
            offerDao.getOfferById(offerId)
        }
    }

    /**
     * Retrieves an offer by its unique offerHash.
     * (Kept for potential other uses, but getOfferByDashZoneAndHash is more specific)
     * @param offerHash The hash of the offer.
     * @return The OfferEntity if found, otherwise null.
     */
    suspend fun getOfferByHash(offerHash: String): OfferEntity? {
        return withContext(Dispatchers.IO) {
            offerDao.getOfferByHash(offerHash)
        }
    }

    /**
     * Retrieves an offer by its dashId, zoneId, and unique offerHash.
     * This is the most specific way to find a unique offer instance.
     *
     * @param dashId The ID of the current dash session.
     * @param zoneId The ID of the current zone.
     * @param offerHash The hash of the offer to retrieve.
     * @return The OfferEntity if found, otherwise null.
     */
    suspend fun getOfferByDashZoneAndHash(dashId: Long, zoneId: Long, offerHash: String): OfferEntity? {
        return withContext(Dispatchers.IO) {
            offerDao.getOfferByDashZoneAndHash(dashId, zoneId, offerHash)
        }
    }

    /**
     * Retrieves all offers for a specific dash session as an observable Flow.
     *
     * @param dashId The ID of the dash session.
     * @return A Flow emitting a list of OfferEntities.
     */
    fun getOffersForDash(dashId: Long): Flow<List<OfferEntity>> {
        // Flow queries from Room are already main-safe and run on a background thread.
        return offerDao.getOffersForDash(dashId)
    }

    /**
     * Retrieves all offers for a specific dash session as a List.
     *
     * @param dashId The ID of the dash session.
     * @return A list of OfferEntities.
     */
    suspend fun getOffersForDashList(dashId: Long): List<OfferEntity> {
        return withContext(Dispatchers.IO) {
            offerDao.getOffersForDashList(dashId)
        }
    }

    /**
     * Retrieves all offers as an observable Flow.
     *
     * @return A Flow emitting a list of all OfferEntities.
     */
    fun getAllOffers(): Flow<List<OfferEntity>> {
        return offerDao.getAllOffers()
    }

    /**
     * Retrieves all offers as a List.
     *
     * @return A list of all OfferEntities.
     */
    suspend fun getAllOffersList(): List<OfferEntity> {
        return withContext(Dispatchers.IO) {
            offerDao.getAllOffersList()
        }
    }

    /**
     * Updates the status of a specific offer.
     *
     * @param offerId The ID of the offer.
     * @param newStatus The new status string.
     */
    suspend fun updateOfferStatus(offerId: Long, newStatus: String) {
        withContext(Dispatchers.IO) {
            offerDao.updateOfferStatus(offerId, newStatus)
        }
    }

    /**
     * Retrieves offers filtered by status as an observable Flow.
     *
     * @param status The status string to filter by.
     * @return A Flow emitting a list of OfferEntities.
     */
    fun getOffersByStatus(status: String): Flow<List<OfferEntity>> {
        return offerDao.getOffersByStatus(status)
    }

    /**
     * Retrieves the most recent offer for a given dash session.
     *
     * @param dashId The ID of the dash session.
     * @return The most recent OfferEntity, or null if none.
     */
    suspend fun getMostRecentOfferForDash(dashId: Long): OfferEntity? {
        return withContext(Dispatchers.IO) {
            offerDao.getMostRecentOfferForDash(dashId)
        }
    }
}
