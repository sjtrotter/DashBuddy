package cloud.trotter.dashbuddy.core.state.di

import javax.inject.Qualifier

/**
 * Dispatcher qualifiers (#341/#352). Defined here (not :app) so coroutine-owning
 * classes in :core:state can take injectable dispatchers and stay testable with
 * virtual time; :app's DispatchersModule provides the bindings.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
