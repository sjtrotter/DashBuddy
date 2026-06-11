package cloud.trotter.dashbuddy.core.data.di

import cloud.trotter.dashbuddy.core.data.settings.PlatformPreferencesRepository
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the domain-facing settings contracts to their :core:data
 * implementations (#355). Pipelines inject [PlatformPreferences]; the
 * settings UI keeps injecting the concrete repository for the write side.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindModule {

    @Binds
    @Singleton
    abstract fun bindPlatformPreferences(
        impl: PlatformPreferencesRepository,
    ): PlatformPreferences
}
