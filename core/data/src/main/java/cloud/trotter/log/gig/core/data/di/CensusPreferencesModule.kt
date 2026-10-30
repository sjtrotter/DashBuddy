package cloud.trotter.log.gig.core.data.di

import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.domain.census.CensusUploadPreferences
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
