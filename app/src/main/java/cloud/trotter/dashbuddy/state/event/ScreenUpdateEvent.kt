package cloud.trotter.dashbuddy.state.event

import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo

data class ScreenUpdateEvent(
    override val timestamp: Long,
    val screenInfo: ScreenInfo?,       // Result of ScreenRecognizer
    val odometer: Double?
) : StateEvent