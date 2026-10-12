package cloud.trotter.dashbuddy.domain.analytics

/** Machine allocation provenance; driver corrections preserve this original basis and measured legs. */
object MilesBasis {
    const val LEG_SUM = "LEG_SUM"
    const val LEGACY_SPAN = "LEGACY_SPAN"
    const val BUDGET_CAPPED = "BUDGET_CAPPED"
    const val UNKNOWN = "UNKNOWN"
}
