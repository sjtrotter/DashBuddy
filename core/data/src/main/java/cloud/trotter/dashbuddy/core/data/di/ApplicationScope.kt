package cloud.trotter.dashbuddy.core.data.di

import javax.inject.Qualifier

/**
 * App-lifetime [kotlinx.coroutines.CoroutineScope] for singletons that host
 * long-lived shared flows (#356 — e.g. the enabled-platforms StateFlow).
 * Provided by :app's DispatchersModule; inject a test scope in unit tests.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
