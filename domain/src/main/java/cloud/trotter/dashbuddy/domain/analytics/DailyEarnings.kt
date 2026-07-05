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
 */
data class DailyEarnings(
    val date: LocalDate,
    val gross: Double,
)
