package cloud.trotter.dashbuddy.statev2

import cloud.trotter.dashbuddy.data.event.AppEventEntity

sealed class AppEffect {
    // 1. Log to Database (The Core of Event Sourcing)
    data class LogEvent(val event: AppEventEntity) : AppEffect()

    // 2. Update UI (The Bubble)
    data class UpdateBubble(val text: String, val isImportant: Boolean = false) : AppEffect()

    // 3. System Commands (e.g. "Keep Screen On", "Play Sound")
    data object PlayNotificationSound : AppEffect()

    data class CaptureScreenshot(
        val filenamePrefix: String, // e.g. "offer" or "payout"
        val metadata: String? = null // Optional context
    ) : AppEffect()

    data class ProcessTipNotification(val rawText: String) : AppEffect()
}