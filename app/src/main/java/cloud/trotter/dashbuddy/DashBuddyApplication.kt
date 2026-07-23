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
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.domain.model.event.EventMetadata
import cloud.trotter.dashbuddy.log.StateAwareTree
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.worker.DailyGasPriceWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class DashBuddyApplication : Application(), Configuration.Provider {

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var stateManagerV2: StateManagerV2

    @Inject
    lateinit var odometerRepository: OdometerRepository

    @Inject
    lateinit var logRepository: LogRepository

    @Inject
    lateinit var devSettingsRepository: DevSettingsRepository

    // Needed for Hilt to inject repositories into WorkManager classes
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var jsonRuleInterpreter: JsonRuleInterpreter

    @Inject
    lateinit var ruleCapabilityRepository: cloud.trotter.dashbuddy.core.data.capability.RuleCapabilityRepository

    @Inject
    lateinit var shadowStoreChainLogger: cloud.trotter.dashbuddy.state.shadow.ShadowStoreChainLogger

    @Inject
    lateinit var analyticsProjector: cloud.trotter.dashbuddy.core.data.analytics.AnalyticsProjector

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
        // Planted before super.onCreate() so injection-time code (DatabaseBackup #690, which runs
        // during Hilt field injection inside super.onCreate()) can log. The plain DebugTree needs no
        // injected dependencies; the StateAwareTree below does, so it stays planted after super.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

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

        Timber.plant(
            StateAwareTree(
                logRepository,
                devSettingsRepository,
                stateProvider
            )
        )

        // 2. Compile the JSON rulesets off the main thread (#361): ~95KB of
        // parse+regex-compile work. The classifier tolerates a null ruleset
        // (classifies UNKNOWN) for the instants before the swap lands.
        //
        // First run the one-shot consent-schema migration (#843): clear any
        // pre-#843 auto-granted capabilities (keep explicit denials) so nothing
        // fires against a stale grant, THEN load the rules. Ordering it before
        // loadDefaults means the reconcile publishes the enumeration into a
        // store that already reflects the no-auto-grant policy; the consent
        // prompt then collects fresh, explicit consent.
        applicationScope.launch {
            ruleCapabilityRepository.migrateConsentSchemaIfNeeded()
            jsonRuleInterpreter.loadDefaults()
        }

        // 3. Initialize State
        stateManagerV2.initialize()
        Timber.i("StateManagerV2 initialized.")

        // 3b. Shadow store-chain projection (#159 / #526 Step 3): debug-only, log-only — observe
        // completed jobs and log the offer→pickup→dropoff→payout chain so we can verify store
        // resolution against real dashes and build corpus before a persisting projector exists.
        if (BuildConfig.DEBUG) {
            shadowStoreChainLogger.start(applicationScope)
        }

        // 3c. Analytics read-model projector (#314): the event-sourced fold of app_events into the
        // durable read-model tables (backfill on first launch, then incremental). NOT debug-gated —
        // this is the product's historical earnings/miles source of truth, not debug telemetry.
        analyticsProjector.start(applicationScope)

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