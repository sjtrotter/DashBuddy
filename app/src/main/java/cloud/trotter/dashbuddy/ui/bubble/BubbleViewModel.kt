package cloud.trotter.dashbuddy.ui.bubble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.data.chat.ChatRepository
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BubbleViewModel @Inject constructor(
    private val bubbleManager: BubbleManager,
    private val chatRepository: ChatRepository
) : ViewModel() {

    // Automatically switches the chat stream when the activeDashId changes
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = bubbleManager.activeDashId.flatMapLatest { dashId ->
        if (dashId != null) {
            chatRepository.getMessages(dashId)
        } else {
            flowOf(emptyList()) // Or show system messages if offline
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Debug helper to test the system
    fun sendTestMessage() {
        val currentId =
            bubbleManager.activeDashId.value ?: "debug_session_${System.currentTimeMillis()}"
        if (bubbleManager.activeDashId.value == null) {
            bubbleManager.startDash(currentId)
        }

        bubbleManager.postMessage(
            "Test Message ${System.currentTimeMillis() % 1000}",
            ChatPersona.Dispatcher
        )
    }
}