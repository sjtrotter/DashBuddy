package cloud.trotter.dashbuddy.core.data.di

import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.domain.census.CensusUploadPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CensusPreferencesModule {
    @Binds
    @Singleton
    abstract fun bindCensusPreferences(impl: DevSettingsRepository): CensusUploadPreferences
}
