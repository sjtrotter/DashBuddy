package cloud.trotter.dashbuddy.domain.analytics

/**
 * The rolling report windows the home glance and future hub aggregate over (#314).
 *
 * These are *labels*, not materialized buckets — the concrete `[start, end)`
 * epoch-millis boundaries are computed at query time against the device's local
 * zone (Monday-anchored weeks match the DoorDash pay week), so "today" flips at
 * local midnight and "this week" flips Monday 00:00 with no stored timezone and
 * no app restart.
 */
enum class AnalyticsPeriod {
    /** Local midnight → now. */
    TODAY,

    /** Monday 00:00 local → now (the pay-week the dasher already sees). */
    THIS_WEEK,

    /** All records, ever. */
    LIFETIME,
}
