package cloud.trotter.dashbuddy

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import cloud.trotter.dashbuddy.data.location.OdometerRepository
import cloud.trotter.dashbuddy.state.StateManagerV2
import cloud.trotter.dashbuddy.ui.bubble.BubbleService
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import cloud.trotter.dashbuddy.log.Level as LogLevel
import cloud.trotter.dashbuddy.log.Logger as Log

@HiltAndroidApp
class DashBuddyApplication : Application() {

    @Inject
    lateinit var stateManagerV2: StateManagerV2

    @Inject
    lateinit var odometerRepository: OdometerRepository


    companion object {
        lateinit var instance: DashBuddyApplication
            private set

        val context: Context
            get() = instance.applicationContext

        val notificationManager: NotificationManager
            get() = instance.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        var bubbleService: BubbleService? = null

        val appPreferences: SharedPreferences by lazy {
            instance.getSharedPreferences("DashBuddyPrefs", MODE_PRIVATE)
        }

        // --- Settings Helpers ---

        fun setDebugMode(enabled: Boolean) {
            appPreferences.edit { putBoolean("debugMode", enabled) }
        }

        fun getDebugMode(): Boolean {
            return appPreferences.getBoolean("debugMode", false)
        }

        fun setLogLevel(level: LogLevel) {
            appPreferences.edit { putString("logLevel", level.name) }
        }

        fun getLogLevel(): LogLevel {
            val levelName = appPreferences.getString("logLevel", null)
            return levelName?.let { LogLevel.valueOf(it) } ?: LogLevel.INFO
        }

        // --- CRASH RECOVERY (NEW) ---

        private const val KEY_CRASH_STATE_JSON = "crash_recovery_state_json"
        private const val KEY_CRASH_TIMESTAMP = "crash_recovery_timestamp"
        private const val STATE_EXPIRY_MS = 30 * 60 * 1000L // 30 Minutes

        fun saveCrashRecoveryState(json: String) {
            appPreferences.edit {
                putString(KEY_CRASH_STATE_JSON, json)
                putLong(KEY_CRASH_TIMESTAMP, System.currentTimeMillis())
            }
        }

        fun getCrashRecoveryState(): String? {
            val savedTime = appPreferences.getLong(KEY_CRASH_TIMESTAMP, 0)
            val now = System.currentTimeMillis()

            // If state is too old (e.g., from yesterday), ignore it.
            if (now - savedTime > STATE_EXPIRY_MS) {
                clearCrashRecoveryState()
                return null
            }

            return appPreferences.getString(KEY_CRASH_STATE_JSON, null)
        }

        fun clearCrashRecoveryState() {
            appPreferences.edit {
                remove(KEY_CRASH_STATE_JSON)
                remove(KEY_CRASH_TIMESTAMP)
            }
        }

        fun createMetadata(): String {
            if (!::instance.isInitialized) {
                return "{ \"test_mode\": true }"
            }
            val odometer = instance.odometerRepository.getCurrentMiles()
            val batteryManager =
                context.getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            val batteryLevel =
                batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val appVersion = packageInfo.versionName

            val metadata = cloud.trotter.dashbuddy.data.event.EventMetadata(
                odometer = odometer,
                batteryLevel = batteryLevel,
                appVersion = appVersion,
                networkType = "UNKNOWN" // You can add connectivity check later
            )

            return com.google.gson.Gson().toJson(metadata)
        }

        // --- Services ---

        @RequiresApi(Build.VERSION_CODES.BAKLAVA)
        fun sendBubbleMessage(message: CharSequence) {
            if (bubbleService != null && BubbleService.isServiceRunningIntentional) {
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
        instance = this

        setDebugMode(true)
        setLogLevel(LogLevel.DEBUG)

        Log.initialize(
            context = context,
            prefs = appPreferences,
            initialDefaultLogLevel = LogLevel.INFO,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            stateManagerV2.initialize()
            Log.i("DashBuddyApp", "StateManagerV2 initialized.")
        } else {
            Log.e("DashBuddyApp", "StateManagerV2 requires API 31 or higher.")
        }

        Log.i("DashBuddyApp", "DashBuddyApplication initialized.")
    }

//    fun startBubbleService() {
//        val serviceIntent = Intent(this, BubbleService::class.java)
//        serviceIntent.putExtra(BubbleService.EXTRA_MESSAGE, "DashBuddy is active!")
//        startForegroundService(serviceIntent)
//    }

    override fun onTerminate() {
        super.onTerminate()
        Log.close()
    }
}