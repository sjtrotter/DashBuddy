package cloud.trotter.log.gig.domain.census

import cloud.trotter.census.contract.SkeletonSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Read-only consent boundary; default consent is off. */
interface CensusUploadPreferences {
    val enabled: Flow<Boolean>
    val baseUrl: Flow<String>
    /** Before the first policy, preserve screen-only publishing. An advertised empty set stays empty. */
    val acceptedSchemaIds: Flow<Set<String>> get() = flowOf(setOf(SkeletonSchema.SCHEMA_ID))
    /** #1620: the dasher's census choices; only the delivery apps they cover are queued. */
    val choices: Flow<CensusChoices>
}

/** The app owns WorkManager; data and feature modules only request a run. */
interface CensusUploadScheduler {
    /**
     * The test build's Upload now in developer options, and an identity reset (#1185). With [wifiOnly] (the census
     * choice) it waits for an unmetered network like the daily run. [replaceQueued] replaces a manual run still waiting
     * out WorkManager backoff; the default keeps it.
     */
    fun enqueueNow(wifiOnly: Boolean, replaceQueued: Boolean = false)

    /**
     * #1620: the daily window, the only scheduled census run. The next run is at [runAtMillis] (ADR-0011 §21), and
     * with [wifiOnly] it waits for an unmetered network. Nothing a screen or a notification does asks for a run.
     */
    fun scheduleDaily(runAtMillis: Long, wifiOnly: Boolean)

    /** Cancels a waiting manual run (the Wi-Fi only choice changed, so its network constraint is stale). */
    fun cancelManual()

    /**
     * #1622: a run that only retries a pending deletion request, on any network, with backoff until the request
     * resolves. It sends no census data.
     */
    fun enqueueDeletion()
}
