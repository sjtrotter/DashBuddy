package cloud.trotter.dashbuddy.data.chat

import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun saveMessage(dashId: String?, persona: ChatPersona, text: String) {
        scope.launch {
            val entity = ChatMessageEntity(
                dashId = dashId,
                senderId = persona.id,
                senderName = persona.name,
                messageText = text,
                iconResId = persona.iconResId
            )
            chatDao.insertMessage(entity)
        }
    }

    fun getMessages(dashId: String) = chatDao.getMessagesForDash(dashId)
}