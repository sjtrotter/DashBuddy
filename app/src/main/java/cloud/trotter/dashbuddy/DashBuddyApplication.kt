package cloud.trotter.dashbuddy

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import cloud.trotter.dashbuddy.bubble.Service as BubbleService
import cloud.trotter.dashbuddy.log.Level as LogLevel
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.Manager as StateManager
import cloud.trotter.dashbuddy.services.accessibility.Service as AccessibilityService
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

class DashBuddyApplication : Application() {

    companion object {
        lateinit var instance: DashBuddyApplication
            private set

        val context: Context
            get() = instance.applicationContext

        val notificationManager: NotificationManager
            get() = instance.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        var bubbleService: BubbleService? = null

        val appPreferences: SharedPreferences
            get() = instance.getSharedPreferences("DashBuddyPrefs", Context.MODE_PRIVATE)

        fun setDebugMode(enabled: Boolean) {
            appPreferences.edit { putBoolean("debugMode", enabled) }
        }

        fun getDebugMode(): Boolean {
            return appPreferences.getBoolean("debugMode", false)
        }

        fun sendBubbleMessage(message: String) {
            if (bubbleService != null &&
                cloud.trotter.dashbuddy.bubble.Service.isServiceRunningIntentional
            ) {
                bubbleService?.showMessageInBubble(message, false)
            } else {
                val serviceIntent = Intent(context, BubbleService::class.java)
                serviceIntent.putExtra(BubbleService.EXTRA_MESSAGE, message)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }

    }

    override fun onCreate() {
        super.onCreate()
        instance = this // Initialize the instance
//        startBubbleService()

        setDebugMode(true)
        Log.initialize(
            context = context,
            prefs = appPreferences,
            defaultLogLevel = LogLevel.INFO,
            debugLogLevel = LogLevel.DEBUG
        )

        Log.i("DashBuddyApp", "DashBuddyApplication initialized.")
    }

    fun startBubbleService() {
        val serviceIntent = Intent(this, BubbleService::class.java)
        serviceIntent.putExtra(BubbleService.EXTRA_MESSAGE, "DashBuddy is active!")
        startForegroundService(serviceIntent)
//        val handler = Handler(Looper.getMainLooper())
//        handler.postDelayed({
//            Log.d("DashBuddyApp", "Executing delayed task to update bubble.")
//            if (bubbleService == null) {
//                Log.e("DashBuddyApp", "bubbleService is NULL. Cannot update bubble.")
//                return@postDelayed // Exit if service is null
//            }
//            try {
//                bubbleService?.showMessageInBubble("Delayed!", false) // No '!!' needed due to the check above
//                Log.d("DashBuddyApp", "'Delayed!' notification posted successfully (or attempt made).")
//            } catch (e: Exception) {
//                Log.e("DashBuddyApp", "Exception during bubbleService.post()", e)
//            }
//
//        }, TimeUnit.SECONDS.toMillis(10))
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.close()
    }
}