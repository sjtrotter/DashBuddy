package cloud.trotter.dashbuddy.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CensusCredentialPreferences

/** Separate from dev settings because credentials must never enter Android backups. */
@Module
@InstallIn(SingletonComponent::class)
object CensusCredentialsModule {
    @Provides
    @Singleton
    @CensusCredentialPreferences
    fun provideCensusCredentials(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("census_credentials") }
}
