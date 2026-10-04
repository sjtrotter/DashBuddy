package cloud.trotter.dashbuddy.core.data.di

import cloud.trotter.dashbuddy.core.data.census.CensusSpool
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import cloud.trotter.dashbuddy.core.data.census.PersistentHealthSink
import cloud.trotter.dashbuddy.domain.census.HealthSink
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

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CensusHealthPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CensusEnvelopeSpool

/** Credentials and generation-scoped health stay separate from settings and outside Android backups. */
@Module
@InstallIn(SingletonComponent::class)
object CensusCredentialsModule {
    @Provides
    @Singleton
    @CensusEnvelopeSpool
    fun envelopeSpool(
        @ApplicationContext context: Context,
        stats: CensusUploadStats,
        @IoDispatcher io: CoroutineDispatcher,
    ): CensusSpool = CensusSpool(File(context.filesDir, "census/envelopes"), stats, io,
        maxFiles = 200, maxDiskBytes = 20L * 1024 * 1024, envelopes = true)

    @Provides
    @Singleton
    @CensusCredentialPreferences
    fun provideCensusCredentials(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("census_credentials") }

    @Provides
    @Singleton
    @CensusHealthPreferences
    fun provideCensusHealth(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("census_health") }

    // Both variants record local diagnostics unconditionally; the worker owns upload consent (#1197).
    @Provides
    fun provideHealthSink(sink: PersistentHealthSink): HealthSink = sink
}
