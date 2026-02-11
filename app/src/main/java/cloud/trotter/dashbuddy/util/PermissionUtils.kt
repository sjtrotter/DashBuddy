package cloud.trotter.dashbuddy.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilityListener
import cloud.trotter.dashbuddy.pipeline.notification.input.NotificationListener

object PermissionUtils {

    /**
     * Checks if all required permissions are granted.
     * Use this in onResume() to detect if a service was killed/disabled.
     */
    fun hasAllEssentialPermissions(context: Context): Boolean {
        return hasPostNotificationsPermission(context) &&
                hasLocationPermission(context) &&
                isAccessibilityServiceEnabled(context) &&
                isNotificationListenerEnabled(context)
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

    // THE IDIOMATIC WAY: Use AccessibilityManager
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

    // Notification Listener Check (Still requires Secure Settings check, no Manager API for this yet)
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val componentName = ComponentName(context, NotificationListener::class.java)
        val flat = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.contains(componentName.flattenToString()) == true
    }
}