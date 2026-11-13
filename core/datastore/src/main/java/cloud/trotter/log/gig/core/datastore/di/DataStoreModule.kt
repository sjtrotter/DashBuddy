package cloud.trotter.log.gig.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import cloud.trotter.log.gig.core.datastore.DataStoreFiles
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Every store name comes from [DataStoreFiles], the one list a full backup and the backup rules read (#1615). */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    @VehiclePreferences
    fun provideVehicleDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.VEHICLES) }

    @Provides
    @Singleton
    @AppPreferences
    fun provideAppPreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.APP_PREFS) }

    @Provides
    @Singleton
    @EventReceiptConsentPreferences
    fun provideEventReceiptConsentDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.CONSENT_EVENT_RECEIPT) }

    @Provides
    @Singleton
    @AppStatePreferences
    fun provideAppStateDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.APP_STATE) }

    @Provides
    @Singleton
    @DevSettingsPreferences
    fun provideDevSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.DEV_SETTINGS) }

    @Provides
    @Singleton
    @OdometerPreferences
    fun provideOdometerDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.ODOMETER_PREFS) }

    @Provides
    @Singleton
    @StrategyPreferences
    fun provideStrategyDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.STRATEGY_PREFS) }

    @Provides
    @Singleton
    @PlatformPreferences
    fun providePlatformDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.PLATFORM_PREFS) }

    @Provides
    @Singleton
    @WeeklyPlanPreferences
    fun provideWeeklyPlanDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.WEEKLY_PLAN) }

    @Provides
    @Singleton
    @RuleCapabilityPreferences
    fun provideRuleCapabilityDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.RULE_CAPABILITY_GRANTS) }

    @Provides
    @Singleton
    @RecognitionNoticePreferences
    fun provideRecognitionNoticeDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(DataStoreFiles.RECOGNITION_NOTICES) }
}