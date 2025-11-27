package cloud.trotter.dashbuddy.data.log.debug

import android.text.SpannableString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DebugLogRepo {

    private const val MAX_LOG_LINES = 1000

    // 1. Use an ArrayDeque as the internal, mutable source of truth.
    //    It is highly efficient at adding to the end and removing from the front.
    private val logMessagesDeque = ArrayDeque<DebugLogItem>(MAX_LOG_LINES)

    // 2. Add a lock for thread safety. StateFlow's 'update' was handling this
    //    for you, but since we are managing the list manually, we must add one.
    private val lock = Any()

    // 3. The StateFlow still holds an immutable List for observers.
    private val _logMessagesFlow = MutableStateFlow<List<DebugLogItem>>(emptyList())
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

        // 4. Update the internal deque inside the lock
        synchronized(lock) {
            // If the deque is full, remove the oldest item first
            if (logMessagesDeque.size >= MAX_LOG_LINES) {
                logMessagesDeque.removeFirst()
            }
            // Add the new item to the end
            logMessagesDeque.addLast(newItem)

            // 5. Update the StateFlow with a new immutable list *after*
            //    the efficient update. This is the *only* copy operation.
            _logMessagesFlow.value = logMessagesDeque.toList()
        }
    }

    /**
     * Clears all messages from the dash log.
     */
    fun clearLogMessages() {
        synchronized(lock) {
            logMessagesDeque.clear()
            _logMessagesFlow.value = emptyList()
        }
    }

    /**
     * Gets the current list of messages directly (non-Flow).
     */
    fun getCurrentLogMessages(): List<DebugLogItem> {
        synchronized(lock) {
            // Return a copy for thread safety
            return logMessagesDeque.toList()
        }
    }
}