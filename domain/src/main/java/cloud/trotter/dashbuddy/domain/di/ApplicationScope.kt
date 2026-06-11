package cloud.trotter.dashbuddy.domain.di

import javax.inject.Qualifier

/**
 * App-lifetime [kotlinx.coroutines.CoroutineScope] for singletons that host
 * long-lived shared flows (#356 — enabled-platforms StateFlow; #361 — the
 * shared PipelineV2 stream). Lives in :domain so every layer module can
 * consume it without cross-layer deps; provided by :app's DispatchersModule.
 * Inject a test scope in unit tests.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
