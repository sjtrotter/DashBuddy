package cloud.trotter.dashbuddy.pipeline.processing

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenRecognizer
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.state.event.NotificationEvent
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.model.NotificationInfo
import cloud.trotter.dashbuddy.log.Logger as Log

object StateContextFactory {

    fun createFromNotification(info: NotificationInfo): NotificationEvent {
        return NotificationEvent(
            timestamp = info.timestamp,
            notification = info
        )
    }

    fun createFromAccessibility(
        uiNode: UiNode
    ): ScreenUpdateEvent {

        // 1. Run Recognition (The heavy part)
        val screenInfo = ScreenRecognizer.identify(uiNode)
        if (DashBuddyApplication.getDebugMode()) {
            Log.d("StateContextFactory", "UI Node Tree: $uiNode")
        }

        // 2. Build Event
        return ScreenUpdateEvent(
            timestamp = System.currentTimeMillis(),
            screenInfo = screenInfo,
            odometer = LocationService.getCurrentOdometer(DashBuddyApplication.context)
        )
    }
}