package cloud.trotter.dashbuddy.domain.model.chat

// The pristine business object.
data class ChatMessage(
    val id: String,
    val dashId: String?,
    val text: String,
    val timestamp: Long,
    val persona: ChatPersona
)