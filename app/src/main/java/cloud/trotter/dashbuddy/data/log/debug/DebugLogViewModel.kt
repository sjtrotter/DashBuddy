package cloud.trotter.dashbuddy.data.log.debug // Or your preferred ViewModel package

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData

class DebugLogViewModel : ViewModel() {

    // Expose the log messages from the repository.
    // Option 1: Expose as LiveData (common for Fragments observing ViewModels)
    val logMessages: LiveData<List<DebugLogItem>> = DebugLogRepo.logMessagesFlow.asLiveData()

    // Option 2: Expose as StateFlow directly (if your Fragment is set up to collect StateFlows lifecycle-awaresly)
    // val logMessages: StateFlow<List<DashLogItem>> = DashLogRepository.logMessagesFlow

    /**
     * Clears all messages from the dash log by calling the repository.
     * Typically called when a new dash starts.
     */
    fun clearLogMessages() {
        DebugLogRepo.clearLogMessages()
    }

    /**
     * Adds a message directly via the ViewModel (which then uses the repository).
     * This might be useful if some UI interaction within the bubble itself needs to log something.
     * However, primary logging from the Service should go directly to the Repository.
     */
    fun addLogMessageViaViewModel(
        message: CharSequence,
    ) {
        DebugLogRepo.addLogMessage(message)
    }
}
