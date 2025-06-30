package cloud.trotter.dashbuddy.data.pay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TipRepo(private val tipDao: TipDao) {

    val allTips: Flow<List<TipEntity>> = tipDao.getAllTips()

    suspend fun insert(tip: TipEntity): Long {
        return withContext(Dispatchers.IO) {
            tipDao.insert(tip)
        }
    }

    suspend fun insertAll(tips: List<TipEntity>): List<Long> {
        return withContext(Dispatchers.IO) {
            tipDao.insertAll(tips)
        }
    }

    fun getTipsForOrder(orderId: Long): Flow<List<TipEntity>> {
        return tipDao.getTipsForOrder(orderId)
    }

    suspend fun getTipsForOrderList(orderId: Long): List<TipEntity> {
        return withContext(Dispatchers.IO) {
            tipDao.getTipsForOrderList(orderId)
        }
    }
}