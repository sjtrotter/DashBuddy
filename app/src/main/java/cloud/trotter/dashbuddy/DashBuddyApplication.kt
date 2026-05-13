package cloud.trotter.dashbuddy

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.log.LogRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.domain.model.event.EventMetadata
import cloud.trotter.dashbuddy.log.StateAwareTree
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.worker.DailyGasPriceWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class DashBuddyApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var stateManagerV2: StateManagerV2

    @Inject
    lateinit var odometerRepository: OdometerRepository

    @Inject
    lateinit var logRepository: LogRepository

    @Inject
    lateinit var appPreferencesRepository: AppPreferencesRepository

    @Inject
    lateinit var devSettingsRepository: DevSettingsRepository

    // Needed for Hilt to inject repositories into WorkManager classes
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var jsonRuleInterpreter: JsonRuleInterpreter

    // Global Context Accessor (Still useful for Utils, but avoid if possible)
    companion object {
        lateinit var instance: DashBuddyApplication
            private set

        val context: Context
            get() = instance.applicationContext

        fun createMetadata(): String {
            // ... (Your existing metadata logic remains unchanged) ...
            if (!::instance.isInitialized) return "{ \"test_mode\": true }"
            val odometer = instance.odometerRepository.getCurrentMiles()
            val battery = (instance.getSystemService(BATTERY_SERVICE) as android.os.BatteryManager)
                .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val ver = instance.packageManager.getPackageInfo(instance.packageName, 0).versionName

            val metadata = EventMetadata(
                odometer = odometer,
                batteryLevel = battery,
                appVersion = ver,
                networkType = "UNKNOWN"
            )
            return com.google.gson.Gson().toJson(metadata)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Setup Logging
        val stateProvider = Provider {
            try {
                val platforms = stateManagerV2.state.value.regions.platforms
                if (platforms.isEmpty()) "Idle"
                else platforms.values.joinToString("|") { "${it.platform.name}:${it.mode.name}" }
            } catch (_: Exception) {
                "Uninitialized"
            }
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(
            StateAwareTree(
                logRepository,
                devSettingsRepository,
                stateProvider
            )
        )

        // 2. Load JSON rule interpreter (dual-run validation; Kotlin matchers remain authoritative)
        jsonRuleInterpreter.loadDefaults()

        // 3. Initialize State
        stateManagerV2.initialize()
        Timber.i("StateManagerV2 initialized.")

        // 4. Schedule Background Tasks
        scheduleBackgroundWorkers()

        Timber.i("DashBuddyApplication initialized.")
    }

    private fun scheduleBackgroundWorkers() {
        // Require internet connection to run the gas fetcher
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val dailyWorkRequest = PeriodicWorkRequestBuilder<DailyGasPriceWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        // KEEP policy ensures we don't accidentally restart the 24-hour clock every time they open the app
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_gas_price_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            dailyWorkRequest
        )
        Timber.i("Background workers verified and scheduled.")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}