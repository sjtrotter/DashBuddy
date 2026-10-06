package cloud.trotter.dashbuddy.domain.census

import cloud.trotter.census.contract.SkeletonSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Read-only consent boundary; default consent is off. */
interface CensusUploadPreferences {
    val enabled: Flow<Boolean>
    val baseUrl: Flow<String>
    /** Before the first policy, preserve screen-only publishing. An advertised empty set stays empty. */
    val acceptedSchemaIds: Flow<Set<String>> get() = flowOf(setOf(SkeletonSchema.SCHEMA_ID))
}

/** The app owns WorkManager; data and feature modules only request a run. */
interface CensusUploadScheduler {
    /** [replaceQueued] (the identity reset, #1185) replaces a manual run still waiting out WorkManager backoff; the default keeps it. */
    fun enqueueNow(replaceQueued: Boolean = false)
    /** A spool that just became non-empty uploads within ~5 minutes without any manual step (dev ask, 2026-10-03); repeated calls keep the first deadline. */
    fun enqueueSoon()
    fun deferUntil(epochMillis: Long)
}
