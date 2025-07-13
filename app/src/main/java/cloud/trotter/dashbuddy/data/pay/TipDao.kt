package cloud.trotter.dashbuddy.data.pay

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tip: TipEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tips: List<TipEntity>): List<Long>

    @Query("SELECT * FROM customer_tips ORDER BY orderId")
    fun getAllTips(): Flow<List<TipEntity>>

    @Query("SELECT * FROM customer_tips WHERE orderId = :orderId")
    fun getTipsForOrder(orderId: Long): Flow<List<TipEntity>>

    @Query("SELECT * FROM customer_tips WHERE orderId = :orderId")
    suspend fun getTipsForOrderList(orderId: Long): List<TipEntity>

    @Query(
        """
        SELECT SUM(T.amount) FROM customer_tips AS T
        INNER JOIN orders AS O ON T.orderId = O.id
        WHERE O.offerId IN (:offerIds)
    """
    )
    fun getTotalTipsForOffersFlow(offerIds: List<Long>): Flow<Double?>

    @Query("SELECT * FROM customer_tips WHERE orderId IN (:orderIds)")
    fun getTipsForOrdersFlow(orderIds: List<Long>): Flow<List<TipEntity>>
}