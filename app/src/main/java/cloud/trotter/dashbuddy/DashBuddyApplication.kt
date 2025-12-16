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
import cloud.trotter.dashbuddy.data.base.DashBuddyDatabase
import cloud.trotter.dashbuddy.data.current.CurrentRepo
import cloud.trotter.dashbuddy.data.customer.CustomerRepo
import cloud.trotter.dashbuddy.data.dash.DashRepo
import cloud.trotter.dashbuddy.data.event.DashEventRepo
import cloud.trotter.dashbuddy.data.event.DropoffEventRepo
import cloud.trotter.dashbuddy.data.event.OfferEventRepo
import cloud.trotter.dashbuddy.data.event.PickupEventRepo
import cloud.trotter.dashbuddy.data.links.dashZone.DashZoneRepo
import cloud.trotter.dashbuddy.ui.fragments.dashhistory.common.DashHistoryRepo
import cloud.trotter.dashbuddy.data.offer.OfferRepo
import cloud.trotter.dashbuddy.data.order.OrderRepo
import cloud.trotter.dashbuddy.data.pay.AppPayRepo
import cloud.trotter.dashbuddy.data.pay.TipRepo
import cloud.trotter.dashbuddy.data.store.StoreRepo
import cloud.trotter.dashbuddy.data.zone.ZoneRepo
import cloud.trotter.dashbuddy.services.bubble.BubbleService
import cloud.trotter.dashbuddy.log.Level as LogLevel
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.data.event.AppEventRepo // Import your new Repo
import cloud.trotter.dashbuddy.services.LocationService

class DashBuddyApplication : Application() {

    companion object {
        lateinit var instance: DashBuddyApplication
            private set

        val context: Context
            get() = instance.applicationContext

        val database: DashBuddyDatabase
            get() = DashBuddyDatabase.getDatabase(context)

        // --- Repositories ---
        val currentRepo: CurrentRepo by lazy { CurrentRepo(database.currentDashDao()) }
        val customerRepo: CustomerRepo by lazy { CustomerRepo(database.customerDao()) }
        val dashRepo: DashRepo by lazy { DashRepo(database.dashDao()) }
        val dashZoneRepo: DashZoneRepo by lazy { DashZoneRepo(database.dashZoneDao()) }
        val offerRepo: OfferRepo by lazy { OfferRepo(database.offerDao()) }
        val orderRepo: OrderRepo by lazy { OrderRepo(database.orderDao()) }
        val zoneRepo: ZoneRepo by lazy { ZoneRepo(database.zoneDao()) }
        val appPayRepo: AppPayRepo by lazy { AppPayRepo(database.appPayDao()) }
        val tipRepo: TipRepo by lazy { TipRepo(database.tipDao()) }
        val storeRepo: StoreRepo by lazy { StoreRepo(database.storeDao()) }
        val pickupEventRepo: PickupEventRepo by lazy { PickupEventRepo(database.pickupEventDao()) }
        val dropoffEventRepo: DropoffEventRepo by lazy { DropoffEventRepo(database.dropoffEventDao()) }
        val offerEventRepo: OfferEventRepo by lazy { OfferEventRepo(database.offerEventDao()) }
        val dashEventRepo: DashEventRepo by lazy { DashEventRepo(database.dashEventDao()) }

        // NEW: The Unified Event Repo
        val appEventRepo: AppEventRepo by lazy { AppEventRepo(database.appEventDao()) }

        val dashHistoryRepo by lazy {
            DashHistoryRepo(
                dashDao = database.dashDao(),
                zoneDao = database.zoneDao(),
            )
        }

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
            val odometer = LocationService.getCurrentOdometer(context)
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

        Log.i("DashBuddyApp", "DashBuddyApplication initialized.")
    }

    fun startBubbleService() {
        val serviceIntent = Intent(this, BubbleService::class.java)
        serviceIntent.putExtra(BubbleService.EXTRA_MESSAGE, "DashBuddy is active!")
        startForegroundService(serviceIntent)
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.close()
    }
}