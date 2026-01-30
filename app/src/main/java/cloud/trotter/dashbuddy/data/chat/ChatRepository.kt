package cloud.trotter.dashbuddy.data.chat

import android.text.Html
import android.text.Spanned
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

    fun saveMessage(dashId: String?, persona: ChatPersona, text: CharSequence) {
        scope.launch {
            // 1. FREEZE: Convert Spannable to HTML String
            val safeText = if (text is Spanned) {
                Html.toHtml(text, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
            } else {
                text.toString()
            }

            val entity = ChatMessageEntity(
                dashId = dashId,
                senderId = persona.id,
                senderName = persona.name,
                messageText = safeText, // Storing "<b>Hello</b>"
                iconResId = persona.iconResId
            )
            chatDao.insertMessage(entity)
        }
    }

    fun getMessages(dashId: String) = chatDao.getMessagesForDash(dashId)
}