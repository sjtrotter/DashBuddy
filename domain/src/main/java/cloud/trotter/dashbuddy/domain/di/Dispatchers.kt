package cloud.trotter.dashbuddy.domain.di

import javax.inject.Qualifier

/**
 * Dispatcher qualifiers (#341/#352, moved here in #364). In :domain — like
 * [ApplicationScope] — so every layer module (state, data, pipeline) can take
 * injectable dispatchers and stay testable with virtual time. :app's
 * DispatchersModule provides the bindings.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
