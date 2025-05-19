package cloud.trotter.dashbuddy

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import cloud.trotter.dashbuddy.bubble.Service as BubbleService
import java.util.concurrent.TimeUnit

class DashBuddyApplication : Application() {

    companion object {
        lateinit var instance: DashBuddyApplication
            private set

        // Publicly accessible context
        val context: Context
            get() = instance.applicationContext

        val notificationManager: NotificationManager
            get() = instance.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        var bubbleService: BubbleService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this // Initialize the instance
//        startBubbleService()
    }

    fun startBubbleService() {
        val serviceIntent = Intent(this, BubbleService::class.java)
        startForegroundService(serviceIntent)
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            Log.d("DashBuddyApp", "Executing delayed task to update bubble.")
            if (bubbleService == null) {
                Log.e("DashBuddyApp", "bubbleService is NULL. Cannot update bubble.")
                return@postDelayed // Exit if service is null
            }
            try {
                bubbleService?.showMessageInBubble("Delayed!", true) // No '!!' needed due to the check above
                Log.d("DashBuddyApp", "'Delayed!' notification posted successfully (or attempt made).")
            } catch (e: Exception) {
                Log.e("DashBuddyApp", "Exception during bubbleService.post()", e)
            }
        }, TimeUnit.SECONDS.toMillis(10))
    }
}