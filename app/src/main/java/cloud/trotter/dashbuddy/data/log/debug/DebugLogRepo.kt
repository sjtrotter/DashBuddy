package cloud.trotter.dashbuddy.data.log.debug

import android.text.SpannableString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update // For concise updates

/**
 * A singleton repository to manage and provide access to the list of dash log messages.
 * This allows different parts of the app (like a Service and a ViewModel) to interact
 * with the same log data.
 */
object DebugLogRepo {

    // Private MutableStateFlow to hold the list of log items.
    // Initialized with an empty list.
    private val _logMessagesFlow = MutableStateFlow<List<DebugLogItem>>(emptyList())

    // Public immutable StateFlow that UI components or ViewModels can observe.
    val logMessagesFlow: StateFlow<List<DebugLogItem>> = _logMessagesFlow.asStateFlow()

    /**
     * Adds a new message to the log.
     *
     * @param message The CharSequence (SpannableString, String, etc.) of the log message.
     */
    fun addLogMessage(message: CharSequence) {
        val spannableMessage = if (message is SpannableString) {
            message
        } else {
            SpannableString(message) // Ensure it's a SpannableString
        }
        val newItem = DebugLogItem(spannableMessage)

        // Update the flow by adding the new item to the current list
        _logMessagesFlow.update { currentList ->
            currentList + newItem
        }
        // Or, if you prefer the older way:
        // _logMessagesFlow.value = _logMessagesFlow.value + newItem
    }

    /**
     * Clears all messages from the dash log.
     * Typically called when a new dash starts.
     */
    fun clearLogMessages() {
        _logMessagesFlow.value = emptyList()
    }

    /**
     * Gets the current list of messages directly (non-Flow).
     * Useful for contexts where a Flow is not ideal for a one-time read,
     * though observing the flow is generally preferred for UI.
     */
    fun getCurrentLogMessages(): List<DebugLogItem> {
        return _logMessagesFlow.value
    }
}
