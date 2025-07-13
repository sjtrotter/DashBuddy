package cloud.trotter.dashbuddy.data.pay

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppPayDao {
    @Query("SELECT * FROM app_pay_types")
    fun getAllPayTypes(): Flow<List<AppPayType>>

    @Query("SELECT * FROM app_pays ORDER BY id DESC")
    fun getAllAppPays(): Flow<List<AppPayEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) // If name exists, IGNORE insert
    suspend fun insertPayType(payType: AppPayType): Long

    @Query("SELECT * FROM app_pay_types WHERE name = :name LIMIT 1")
    suspend fun getPayTypeByName(name: String): AppPayType?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(appPay: AppPayEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(appPays: List<AppPayEntity>): List<Long>

    @Query("SELECT * FROM app_pays WHERE offerId = :offerId")
    fun getPayComponentsForOffer(offerId: Long): Flow<List<AppPayEntity>>

    @Query("SELECT * FROM app_pays WHERE offerId = :offerId")
    suspend fun getPayComponentsForOfferList(offerId: Long): List<AppPayEntity>

    @Query("SELECT * FROM app_pay_types WHERE id = :id LIMIT 1")
    suspend fun getPayTypeById(id: Long): AppPayType?

    @Query("SELECT SUM(amount) FROM app_pays WHERE offerId IN (:offerIds)")
    fun getTotalAppPayForOffersFlow(offerIds: List<Long>): Flow<Double?>

    @Query("SELECT * FROM app_pays WHERE offerId IN (:offerIds)")
    fun getPayComponentsForOffersFlow(offerIds: List<Long>): Flow<List<AppPayEntity>>
}


