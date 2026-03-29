package cloud.trotter.dashbuddy.core.data.chat

import cloud.trotter.dashbuddy.core.database.chat.ChatDao
import cloud.trotter.dashbuddy.core.database.chat.mappers.toDomain
import cloud.trotter.dashbuddy.core.database.chat.mappers.toEntity
import cloud.trotter.dashbuddy.domain.model.chat.ChatMessage
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao
) {
    // Queries specific dash messages and maps them to pure Domain models
    fun getMessages(dashId: String): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForDash(dashId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun saveMessage(dashId: String?, text: String, persona: ChatPersona) {
        val domainMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            dashId = dashId, // Saved!
            text = text,
            timestamp = System.currentTimeMillis(),
            persona = persona
        )

        chatDao.insertMessage(domainMessage.toEntity())
    }

    fun getAllDashIds(): Flow<List<String>> {
        return chatDao.getAllDashIds()
    }
}