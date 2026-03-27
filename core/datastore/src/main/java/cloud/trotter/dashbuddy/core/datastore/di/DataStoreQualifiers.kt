package cloud.trotter.dashbuddy.core.datastore.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppStatePreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DevSettingsPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OdometerPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StateRecoveryPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StrategyPreferences