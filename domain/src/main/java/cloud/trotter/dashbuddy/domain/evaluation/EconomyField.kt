package cloud.trotter.dashbuddy.domain.evaluation

/**
 * The individual fields of [UserEconomy] that the user can explicitly set.
 * Used as the value-type of `UserEconomy.userSetFields` so the UI can show a
 * "(default)" badge on fields the user hasn't yet customized.
 *
 * A field becomes "user-set" when the user writes a value through a settings
 * screen (wizard or Personal Economy settings). The set persists in DataStore
 * under the `USER_SET_ECONOMY_FIELDS` key.
 */
enum class EconomyField {
    VEHICLE_CLASS,
    VEHICLE_MPG,
    GAS_PRICE,
    AVG_MIN_PER_MILE,
    BASE_PICKUP_MIN,
    TIRE_COST,
    TIRE_LIFETIME,
    OIL_COST,
    OIL_INTERVAL,
    BRAKES_COST,
    BRAKES_INTERVAL,
    FLUIDS_COST,
    FLUIDS_INTERVAL,
    MISC_YEARLY,
    MISC_YEARLY_MI,
    INCLUDE_DEPRECIATION,
    PURCHASE_PRICE,
    TOTAL_LIFETIME_MI,
    INSURANCE_DELTA,
    REGISTRATION_DELTA,
    EXPECTED_ANNUAL_DASH_MI,
    PHONE_PLAN_TOTAL,
    PHONE_PLAN_LINES,
    PHONE_DASH_PERCENT,
}
