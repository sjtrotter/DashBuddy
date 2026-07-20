package cloud.trotter.dashbuddy.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilityListener
import cloud.trotter.dashbuddy.core.pipeline.notification.input.NotificationListener

object PermissionUtils {

    /**
     * Checks if all required permissions are granted.
     * Use this in onResume() to detect if a service was killed/disabled.
     */
    fun hasAllEssentialPermissions(context: Context): Boolean {
        return hasPostNotificationsPermission(context) &&
                hasLocationPermission(context) &&
                isAccessibilityServiceEnabled(context) &&
                isNotificationListenerEnabled(context) &&
                hasFullBubblePreference(context)
    }

    // Standard Runtime Permission
    fun hasPostNotificationsPermission(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // Standard Runtime Permission
    fun hasLocationPermission(context: Context): Boolean {
        val fine =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    // Accessibility Permission
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

        // Get all enabled services
        val enabledServices =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        // Check if OUR service is in the list
        val expectedName = AccessibilityListener::class.java.name
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                    it.resolveInfo.serviceInfo.name == expectedName
        }
    }

    // Notification Listener Check
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val componentName = ComponentName(context, NotificationListener::class.java)
        val flat = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(componentName.flattenToString()) == true
    }

    // Bubble Permissions
    fun hasFullBubblePreference(context: Context): Boolean {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notificationManager.bubblePreference == NotificationManager.BUBBLE_PREFERENCE_ALL
        } else {
            // bubblePreference is API 31+; on API 30 fall back to the closest
            // semantic equivalent — "are bubbles allowed for this app".
            notificationManager.areBubblesAllowed()
        }
    }
}