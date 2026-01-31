package cloud.trotter.dashbuddy.di

import cloud.trotter.dashbuddy.state.effects.DefaultEffectHandler
import cloud.trotter.dashbuddy.state.effects.EffectHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EffectModule {

    @Binds
    @Singleton
    abstract fun bindEffectHandler(
        impl: DefaultEffectHandler
    ): EffectHandler
}