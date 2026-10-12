package cloud.trotter.dashbuddy.domain.analytics

/** Machine allocation provenance; driver corrections preserve this original basis and measured legs. */
enum class MilesBasis {
    LEG_SUM,
    LEGACY_SPAN,
    BUDGET_CAPPED,
    UNKNOWN,
}
