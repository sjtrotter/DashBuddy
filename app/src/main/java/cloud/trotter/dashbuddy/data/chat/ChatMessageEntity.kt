package cloud.trotter.dashbuddy.data.chat

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    indices = [Index("dashId")] // Speed up lookups by Dash ID
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dashId: String?, // Nullable for global system messages
    val timestamp: Long = System.currentTimeMillis(),
    val senderId: String,
    val senderName: String,
    val messageText: String,
    val iconResId: Int
)