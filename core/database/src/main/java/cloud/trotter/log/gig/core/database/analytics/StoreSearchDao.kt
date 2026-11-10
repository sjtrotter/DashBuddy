package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * #1540: the store search's reads, kept out of [AnalyticsDao] (near the size limit). [offerNames] and [deliveryNames]
 * are names and ids only, every shift: the search filters them in Kotlin before any page is taken, so a match in an
 * old shift is found. The batched reads load one page of matched shifts' timelines in three flows, not three per
 * shift. A row with no shift is out of scope (it has no shift page). Each re-emits on every projector commit to its
 * table (Room invalidation).
 */
@Dao
interface StoreSearchDao {
    /** Every offer of a shift that recorded a store, any outcome. */
    @Query(
        """SELECT eventSequenceId, sessionId, merchantName AS name FROM offer_records
           WHERE sessionId IS NOT NULL AND merchantName IS NOT NULL"""
    )
    fun offerNames(): Flow<List<StoreNameRow>>

    /** Every delivery of a shift that recorded a store. */
    @Query(
        """SELECT eventSequenceId, sessionId, storeName AS name FROM delivery_records
           WHERE sessionId IS NOT NULL AND storeName IS NOT NULL"""
    )
    fun deliveryNames(): Flow<List<StoreNameRow>>

    /** The offers of [sessionIds], every outcome, as [AnalyticsDao.offersForSession] orders one shift's. */
    @Query("SELECT * FROM offer_records WHERE sessionId IN (:sessionIds) ORDER BY decidedAt ASC, eventSequenceId ASC")
    fun offersForSessions(sessionIds: List<String>): Flow<List<OfferRecordEntity>>

    @Query("SELECT * FROM pickup_records WHERE sessionId IN (:sessionIds) ORDER BY eventSequenceId ASC")
    fun pickupsForSessions(sessionIds: List<String>): Flow<List<PickupRecordEntity>>

    @Query("SELECT * FROM delivery_records WHERE sessionId IN (:sessionIds) ORDER BY completedAt ASC")
    fun deliveriesForSessions(sessionIds: List<String>): Flow<List<DeliveryRecordEntity>>
}

/** One recorded store name and where it sits: a merchant, never customer data. */
data class StoreNameRow(val eventSequenceId: Long, val sessionId: String, val name: String)
