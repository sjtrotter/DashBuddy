package cloud.trotter.dashbuddy.ui.main.dashboard

import androidx.annotation.StringRes
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import java.time.LocalDate

/**
 * Immutable state for the home screen (Principle 1 — UDF), which #977 (redesign stage 4 of #969,
 * brief §2) turned into **"Today"**: a forward-looking glance — today's plan projected from the
 * driver's own history, so-far-today numbers, a week recap pointer, review items, then the entry
 * tiles.
 *
 * The dashboard is still a **review / plan** surface, not a live-while-dashing mirror of the bubble
 * HUD (#657): the bubble owns the strictly-live glance on a task. But it is no longer *static* —
 * stage 4 makes two things genuinely time-derived, and both follow Reactive UI rule 2 by holding the
 * ANCHOR here and deriving the value in the composable:
 *  - the header clock, derived from `rememberNow()`;
 *  - the plan strip's spent hours + best-window pick, derived from [heatmap] + [today] + that same
 *    ticker (crossing an hour boundary re-dims and re-picks with no state emission).
 *
 * Everything else re-emits from the read-model itself: the economics flows are Room-invalidation
 * Flows re-anchored at local midnight, so a settled figure never needs a clock (a historical
 * window's $/hr is fixed).
 *
 * Permissions stay a composable-local OS re-check (re-read on every `ON_RESUME`), so they are
 * intentionally NOT modelled here.
 */
data class DashboardUiState(
    val isFirstRun: Boolean = true,
    /**
     * The home status headline — a `@StringRes` id (#428 Half A), resolved with
     * `stringResource(...)` at the Compose layer so this Context-free `@HiltViewModel` never
     * carries resolved copy. Since #977 it is rendered as the header's status pill; the vocabulary
     * itself is unchanged.
     */
    @param:StringRes val statusText: Int = R.string.dashboard_status_offline,
    /**
     * True while a dash session is active (focused region present, mode != Offline),
     * registry-resolved (never a `== DoorDash` literal). Drives the status pill's tone and the
     * bubble pointer.
     */
    val isDashing: Boolean = false,
    /**
     * The device's current local date — re-emitted at local midnight, so the header date, the plan
     * strip's weekday row, and the week window all roll over without a restart.
     */
    val today: LocalDate = LocalDate.now(),
    /** Frozen-net economics for the rolling `TODAY` window — the "So far today" tiles. */
    val todayEconomics: PeriodEconomics = PeriodEconomics.EMPTY,
    /**
     * The driver's own realized net $/hr by hour-of-week, **lifetime** — the plan strip's only
     * source (brief §2 marks it free: the Patterns tab already computes it). Empty grid until data
     * accrues, in which case the strip states the thin data rather than projecting.
     */
    val heatmap: EarningsHeatmap = EarningsHeatmap.EMPTY,
    /** Frozen-net economics for the current Monday-anchored pay week — the "This week" block. */
    val weekEconomics: PeriodEconomics = PeriodEconomics.EMPTY,
    /** Last week's economics — the delta chip's comparison (null only before the read lands). */
    val previousWeekEconomics: PeriodEconomics? = null,
    /** This week's per-day kept money — the week block's 7-point sparkline (#970 §7.3). */
    val weekDailyEarnings: List<DailyEarnings> = emptyList(),
    /**
     * The week's still-open orphan-offer mismatches (#810 B2) — one input to the review-items row.
     * The other inputs ride on [weekEconomics] (unattributed / over-attributed / "(No session)"),
     * which is why the row is scoped to the same week the block above it describes: a data-quality
     * chore from yesterday must not vanish just because Home's headline is "today".
     */
    val orphanOfferGroups: List<OrphanOfferGroup> = emptyList(),
)
