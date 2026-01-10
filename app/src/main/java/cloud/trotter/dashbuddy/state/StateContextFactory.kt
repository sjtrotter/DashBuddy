package cloud.trotter.dashbuddy.state

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.services.accessibility.click.ClickInfo
import cloud.trotter.dashbuddy.services.accessibility.click.ClickParser
import cloud.trotter.dashbuddy.services.accessibility.notification.NotificationInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenRecognizerV2
import cloud.trotter.dashbuddy.util.AccNodeUtils
import java.util.Date

/**
 * pure pipeline component: Takes raw Android events -> Produces StateContext
 */
object StateContextFactory {

    fun createFromNotification(info: NotificationInfo): StateContext {
        return StateContext(
            timestamp = info.timestamp,
            eventType = -999, // Specific ID for Notification Listener
            eventTypeString = "NOTIFICATION_LISTENER",
            packageName = info.packageName,
            notification = info,
            // Explicitly null for screen-related items
            rootNode = null,
            rootUiNode = null
        )
    }

    fun createFromAccessibility(
        event: AccessibilityEvent,
        rootNode: AccessibilityNodeInfo,
        rootUiNode: UiNode,
        rootNodeTexts: List<String>
    ): StateContext {
        // 1. Gather Dependencies (Can be mocked in tests if we injected them)
        val odometer = LocationService.getCurrentOdometer(DashBuddyApplication.context)

        // 2. Extract Source Info
        val sourceNodeTexts = mutableListOf<String>()
        event.source?.let { AccNodeUtils.extractTexts(it, sourceNodeTexts) }

        // 3. Parse Clicks
        var clickInfo: ClickInfo = ClickInfo.NoClick
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            clickInfo = ClickParser.parse(sourceNodeTexts)
        }

        // 4. Build Partial Context
        val partialContext = StateContext(
            timestamp = Date().time,
            odometerReading = odometer,
            eventType = event.eventType,
            eventTypeString = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = event.packageName,
            rootNode = rootNode,
            rootUiNode = rootUiNode,
            rootNodeTexts = rootNodeTexts,
            sourceClassName = event.className,
            sourceNode = event.source,
            sourceNodeTexts = sourceNodeTexts,
            clickInfo = clickInfo,
        )

        // 5. Run Screen Recognition (Enrichment)
        // This is now the only place where recognition happens
        return partialContext.copy(
            screenInfo = ScreenRecognizerV2.identify(partialContext)
        )
    }
}