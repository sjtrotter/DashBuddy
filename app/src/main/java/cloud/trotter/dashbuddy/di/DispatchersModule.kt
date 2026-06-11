package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.di.DefaultDispatcher
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton
import cloud.trotter.dashbuddy.BuildConfig
import javax.inject.Named

/**
 * Bindings for the injectable dispatchers so coroutine-owning singletons are
 * testable with virtual time (#341/#352), plus the app-lifetime scope for
 * long-lived shared flows (#356). The dispatcher qualifiers live in
 * `:core:state` (`core.state.di`), the scope qualifier in `:core:data`
 * (`core.data.di`); the data-layer hygiene pass (#364) migrates the remaining
 * hardcoded scopes onto these.
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

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Build-variant flag for layer modules (#364) — they must not borrow
     *  another module's BuildConfig for it. */
    @Provides
    @Named("isDebug")
    fun provideIsDebug(): Boolean = BuildConfig.DEBUG
}
