package cloud.trotter.dashbuddy.state.effects

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.data.location.OdometerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.log.Logger as Log

@Singleton
class OdometerEffectHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val odometerRepository: OdometerRepository,
    private val notificationManager: NotificationManager // <--- Injected!
) {

    private val tag = "OdometerEffect"
    private val notificationId = 101
    private val channelId = "LocationServiceChannel" // Keeping ID for compatibility

    init {
        createNotificationChannel()
    }

    fun startUp() {
        Log.i(tag, "Effect: Starting Odometer & Notification")
        try {
            // 1. Start the Logic (Coroutines Job)
            odometerRepository.startTracking()

            // 2. Post the Notification (User Trust UI)
            val notification = createNotification()
            notificationManager.notify(notificationId, notification)

        } catch (e: Exception) {
            Log.e(tag, "Failed to start Odometer", e)
        }
    }

    fun shutDown() {
        Log.i(tag, "Effect: Stopping Odometer")
        try {
            // 1. Stop the Logic (Kills GPS)
            odometerRepository.stopTracking()

            // 2. Remove the Notification
            notificationManager.cancel(notificationId)

        } catch (e: Exception) {
            Log.e(tag, "Failed to stop Odometer", e)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(context, channelId)
            .setContentTitle("Odometer Active")
            .setContentText("")
            .setSmallIcon(R.drawable.ic_location) // Make sure this drawable exists!
            .setOngoing(true) // Makes it "sticky" so user knows it's running
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Odometer Active",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }
}