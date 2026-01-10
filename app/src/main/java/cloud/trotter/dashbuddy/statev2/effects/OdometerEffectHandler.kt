package cloud.trotter.dashbuddy.statev2.effects

import android.content.Intent
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.log.Logger as Log


object OdometerEffectHandler {

    private const val TAG = "OdometerEffect"

    fun startUp() {
        Log.i(TAG, "Effect: Starting Odometer Service")
        try {
            val intent = Intent(
                DashBuddyApplication.context,
                LocationService::class.java
            )
            DashBuddyApplication.context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Odometer", e)
        }
    }

    fun shutDown() {
        Log.i(TAG, "Effect: Stopping Odometer Service")
        try {
            val intent = Intent(
                DashBuddyApplication.context,
                LocationService::class.java
            )
            DashBuddyApplication.context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Odometer", e)
        }
    }
}