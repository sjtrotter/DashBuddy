package cloud.trotter.dashbuddy.pipeline.processing

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.services.accessibility.notification.NotificationInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenRecognizerV2
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.util.AccNodeUtils
import java.util.Date

object StateContextFactory {

    private var lastStructureHash: Int? = null
    private var lastContentHash: Int? = null

    // --- NOTIFICATION FACTORY ---
    fun createFromNotification(info: NotificationInfo): StateContext {
        return StateContext(
            timestamp = info.timestamp,
            eventType = -999, // Custom ID for "Notification"
            eventTypeString = "NOTIFICATION_LISTENER",
            packageName = info.packageName,
            notification = info,

            // Notifications have no screen structure attached
            rootNode = null,
            rootUiNode = null
        )
    }

    // --- ACCESSIBILITY FACTORY ---
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun createFromAccessibility(
        event: AccessibilityEvent,
        rootNode: AccessibilityNodeInfo
    ): StateContext? {
        val rootUiNode = UiNode.from(rootNode) ?: return null

        // Diffing Strategy
        val structureHash = rootUiNode.getStructuralHashCode()
        val contentHash = rootUiNode.getContentHashCode()

        if (structureHash == lastStructureHash && contentHash == lastContentHash) {
            if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
                return null
            }
        }

        lastStructureHash = structureHash
        lastContentHash = contentHash

        val rootNodeTexts = mutableListOf<String>()
        AccNodeUtils.extractTexts(rootNode, rootNodeTexts)

        val context = StateContext(
            timestamp = Date().time,
            odometerReading = LocationService.getCurrentOdometer(DashBuddyApplication.context),
            eventType = event.eventType,
            eventTypeString = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = event.packageName,
            rootNode = rootNode,
            rootUiNode = rootUiNode,
            rootNodeTexts = rootNodeTexts,
            currentDashState = null
        )

        return context.copy(
            screenInfo = ScreenRecognizerV2.identify(context)
        )
    }
}