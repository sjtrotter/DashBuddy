package cloud.trotter.dashbuddy.core.database.chat

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    indices = [Index("dashId")]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val dashId: String?,
    val text: String,
    val timestamp: Long,
    val personaType: String,
    val personaName: String
)