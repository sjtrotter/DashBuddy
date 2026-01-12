package cloud.trotter.dashbuddy.pipeline.processing

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenRecognizer
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.statev2.model.NotificationInfo
import cloud.trotter.dashbuddy.statev2.event.NotificationEvent
import cloud.trotter.dashbuddy.statev2.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.statev2.event.StateEvent

object StateContextFactory {

    // --- NOTIFICATION FACTORY ---
    fun createFromNotification(info: NotificationInfo): StateEvent {
        return NotificationEvent(
            timestamp = info.timestamp,
            notification = info,
        )
    }

    // --- ACCESSIBILITY FACTORY ---
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun createFromAccessibility(
        rootNode: AccessibilityNodeInfo
    ): StateEvent? {
        val uiNode = UiNode.from(rootNode) ?: return null
        val screenInfo = ScreenRecognizer.identify(uiNode)

        return ScreenUpdateEvent(
            timestamp = System.currentTimeMillis(),
            screenInfo = screenInfo,
            odometer = LocationService.getCurrentOdometer(
                DashBuddyApplication.context
            )
        )
    }
}