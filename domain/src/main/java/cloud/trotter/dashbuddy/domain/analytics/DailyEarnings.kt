package cloud.trotter.dashbuddy.domain.analytics

import java.time.LocalDate

/**
 * One local calendar day of a bounded period's earnings (#315 H6) — the per-day earnings-chart input.
 *
 * A [dailyEarnings][cloud.trotter.dashbuddy.core.data.analytics] list is a **complete axis**, not a
 * sparse one: every day of the window is present, in order, with gap days carrying `0.0` [gross] (a
 * driver who didn't dash Tuesday still gets a Tuesday bar). [gross] is the reported-authoritative
 * per-session gross (summary total when present, else that session's Σ delivered pay — the same
 * definition as the read-model's gross), attributed **wholly to the session's start day**
 * (session-anchored periods, #655): a dash begun 11:50pm counts entirely on that evening, never split
 * across midnight.
 *
 * [net] is the same day's **kept** money (#970 / brief §7.3) — the frozen per-delivery `netProfit`
 * plus cash tips plus that session's unattributed remainder, i.e. the per-day decomposition of
 * [PeriodEconomics.netProfit] under the identical session-anchored bucketing. It exists because the
 * recap hero's sparkline must plot kept money, not gross: a gross sparkline flatters a day whose
 * miles ate it. Σ [net] over a window's days therefore equals that window's `PeriodEconomics.netProfit`
 * by construction (both fold the same frozen columns) — never recomputed, never re-costed.
 */
data class DailyEarnings(
    val date: LocalDate,
    val gross: Double,
    val net: Double = 0.0,
)
