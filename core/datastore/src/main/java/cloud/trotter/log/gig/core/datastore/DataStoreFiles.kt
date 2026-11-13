package cloud.trotter.log.gig.core.datastore

/**
 * The one list of this module's Preferences DataStore files (#1615).
 * [cloud.trotter.log.gig.core.datastore.di.DataStoreModule] names each store from here, so a new store cannot be
 * added without landing in [ALL].
 *
 * [BACKED_UP] is the set a full backup file carries and Android backup keeps: history-shaped settings that follow the
 * driver to another install. Every other name in [ALL] is device-local (consent and automation grants, developer
 * settings, recognition notice timing) and is excluded in `backup_rules.xml` and `data_extraction_rules.xml`;
 * `BackupRulesStorePartitionTest` in `:app` holds the two lists to that split.
 *
 * Each name is the file stem: the store lives at `filesDir/datastore/<name>.preferences_pb`.
 */
object DataStoreFiles {
    const val VEHICLES = "vehicles"
    const val APP_PREFS = "app_prefs"
    const val CONSENT_EVENT_RECEIPT = "consent_event_receipt"
    const val APP_STATE = "app_state"
    const val DEV_SETTINGS = "dev_settings"
    const val ODOMETER_PREFS = "odometer_prefs"
    const val STRATEGY_PREFS = "strategy_prefs"
    const val PLATFORM_PREFS = "platform_prefs"
    const val WEEKLY_PLAN = "weekly_plan"
    const val RULE_CAPABILITY_GRANTS = "rule_capability_grants"
    const val RECOGNITION_NOTICES = "recognition_notices"

    /** Every store the DataStore module provides. */
    val ALL: Set<String> = setOf(
        VEHICLES,
        APP_PREFS,
        CONSENT_EVENT_RECEIPT,
        APP_STATE,
        DEV_SETTINGS,
        ODOMETER_PREFS,
        STRATEGY_PREFS,
        PLATFORM_PREFS,
        WEEKLY_PLAN,
        RULE_CAPABILITY_GRANTS,
        RECOGNITION_NOTICES,
    )

    /** The stores a full backup carries and Android backup keeps; the rest of [ALL] stays on the device. */
    val BACKED_UP: Set<String> = setOf(
        VEHICLES,
        APP_PREFS,
        APP_STATE,
        ODOMETER_PREFS,
        STRATEGY_PREFS,
        PLATFORM_PREFS,
        WEEKLY_PLAN,
    )
}
