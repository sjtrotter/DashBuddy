package cloud.trotter.dashbuddy.data.pay

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppPayDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) // If name exists, IGNORE insert
    suspend fun insertPayType(payType: AppPayType): Long

    @Query("SELECT * FROM app_pay_types WHERE name = :name LIMIT 1")
    suspend fun getPayTypeByName(name: String): AppPayType?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(appPay: AppPayEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(appPays: List<AppPayEntity>): List<Long>

    @Query("SELECT * FROM app_pay_components WHERE offerId = :offerId")
    fun getPayComponentsForOffer(offerId: Long): Flow<List<AppPayEntity>>

    @Query("SELECT * FROM app_pay_components WHERE offerId = :offerId")
    suspend fun getPayComponentsForOfferList(offerId: Long): List<AppPayEntity>
}


