package cloud.trotter.dashbuddy.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert
    suspend fun insertMessage(message: ChatMessageEntity)

    // Live stream for the current active dash
    @Query("SELECT * FROM chat_messages WHERE dashId = :dashId ORDER BY timestamp ASC")
    fun getMessagesForDash(dashId: String): Flow<List<ChatMessageEntity>>

    // The "Poor Man's Join": Get a list of all unique Dash IDs to build the history list later
    @Query("SELECT DISTINCT dashId FROM chat_messages WHERE dashId IS NOT NULL ORDER BY timestamp DESC")
    fun getAllDashIds(): Flow<List<String>>
}