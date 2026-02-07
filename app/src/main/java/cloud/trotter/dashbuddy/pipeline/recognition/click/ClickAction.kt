package cloud.trotter.dashbuddy.pipeline.recognition.click

enum class ClickAction {
    ACCEPT_OFFER,
    DECLINE_OFFER,     // Opens menu
    CONFIRM_DECLINE,   // Kills offer
    UNKNOWN
}