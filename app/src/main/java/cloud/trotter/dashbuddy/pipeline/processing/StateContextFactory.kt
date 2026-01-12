package cloud.trotter.dashbuddy.pipeline.processing

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenRecognizer
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.statev2.event.NotificationEvent
import cloud.trotter.dashbuddy.statev2.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.statev2.model.NotificationInfo

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

        // 2. Build Event
        return ScreenUpdateEvent(
            timestamp = System.currentTimeMillis(),
            screenInfo = screenInfo,
            odometer = LocationService.getCurrentOdometer(DashBuddyApplication.context)
        )
    }
}