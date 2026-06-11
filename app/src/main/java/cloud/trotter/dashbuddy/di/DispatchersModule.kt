package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.core.state.di.DefaultDispatcher
import cloud.trotter.dashbuddy.core.state.di.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Bindings for the injectable dispatchers so coroutine-owning singletons are
 * testable with virtual time (#341/#352). The qualifier annotations live in
 * `:core:state` (`core.state.di`) so state-layer classes can use them too;
 * the data-layer hygiene pass (#364) migrates its hardcoded scopes onto these.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
