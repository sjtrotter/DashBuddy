package cloud.trotter.dashbuddy.domain.model.state

import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo

data class ScreenUpdateEvent(
    override val timestamp: Long,
    val screenInfo: ScreenInfo?,
    val odometer: Double?
) : StateEvent