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
annotation class StrategyPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlatformPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RuleCapabilityPreferences
/** #981 — the driver's saved weekly plans (a user artifact, not a rebuildable projection). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WeeklyPlanPreferences
