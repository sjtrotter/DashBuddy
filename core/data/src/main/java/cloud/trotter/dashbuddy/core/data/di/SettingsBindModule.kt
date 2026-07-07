package cloud.trotter.dashbuddy.core.data.di

import cloud.trotter.dashbuddy.core.data.settings.PlatformPreferencesRepository
import cloud.trotter.dashbuddy.domain.settings.GraceConfigProvider
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import dagger.Binds
import dagger.Module
import dagger.Provides
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

    companion object {
        /**
         * The per-platform grace/timing snapshot provider (#438 item 6, vet M7).
         * Reads [PlatformPreferences.graceConfig]`.value` synchronously — the
         * `evidenceConfig.value` snapshot pattern — so the pure steppers and
         * `EffectMap` never collect a Flow inside a reducer. See
         * [GraceConfigProvider] for the pre-accepted replay-determinism tradeoff.
         */
        @Provides
        @Singleton
        fun provideGraceConfigProvider(
            preferences: PlatformPreferences,
        ): GraceConfigProvider = GraceConfigProvider { preferences.graceConfig.value }
    }
}
