package cloud.trotter.dashbuddy.data.offer // Or your specific DAO package

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow // For observable queries

/**
 * Data Access Object for the OfferEntity.
 */
@Dao
interface OfferDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffer(offer: OfferEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffers(offers: List<OfferEntity>): List<Long>

    @Update
    suspend fun updateOffer(offer: OfferEntity)

    @Delete
    suspend fun deleteOffer(offer: OfferEntity)

    @Query("DELETE FROM offers")
    suspend fun deleteAllOffers()

    @Query("SELECT * FROM offers WHERE id = :offerId")
    suspend fun getOfferById(offerId: Long): OfferEntity?

    @Query("SELECT * FROM offers WHERE offerHash = :offerHash LIMIT 1")
    suspend fun getOfferByHash(offerHash: String): OfferEntity? // Kept for potential other uses

    /**
     * Retrieves an offer by its dashId, zoneId, and unique offerHash.
     * This is the most specific way to find a unique offer instance.
     *
     * @param dashId The ID of the current dash session.
     * @param zoneId The ID of the current zone.
     * @param offerHash The hash of the offer to retrieve.
     * @return The OfferEntity if found, otherwise null.
     */
    @Query("SELECT * FROM offers WHERE dashId = :dashId AND zoneId = :zoneId AND offerHash = :offerHash LIMIT 1")
    suspend fun getOfferByDashZoneAndHash(
        dashId: Long,
        zoneId: Long,
        offerHash: String
    ): OfferEntity?

    @Query("SELECT * FROM offers WHERE dashId = :dashId ORDER BY timestamp DESC")
    fun getOffersForDash(dashId: Long): Flow<List<OfferEntity>>

    @Query("SELECT * FROM offers WHERE dashId = :dashId ORDER BY timestamp DESC")
    suspend fun getOffersForDashList(dashId: Long): List<OfferEntity>

    @Query("SELECT * FROM offers ORDER BY timestamp DESC")
    fun getAllOffers(): Flow<List<OfferEntity>>

    @Query("SELECT * FROM offers ORDER BY timestamp DESC")
    suspend fun getAllOffersList(): List<OfferEntity>

    @Query("UPDATE offers SET status = :newStatus WHERE id = :offerId")
    suspend fun updateOfferStatus(offerId: Long, newStatus: String)

    @Query("SELECT * FROM offers WHERE status = :status ORDER BY timestamp DESC")
    fun getOffersByStatus(status: String): Flow<List<OfferEntity>>

    @Query("SELECT * FROM offers WHERE dashId = :dashId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getMostRecentOfferForDash(dashId: Long): OfferEntity?

}
