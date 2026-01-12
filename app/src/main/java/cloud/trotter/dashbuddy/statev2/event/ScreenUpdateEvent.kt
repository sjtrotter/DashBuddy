package cloud.trotter.dashbuddy.statev2.event

import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo

data class ScreenUpdateEvent(
    override val timestamp: Long,
    val screenInfo: ScreenInfo?,       // Result of ScreenRecognizer
    val odometer: Double?
) : StateEvent