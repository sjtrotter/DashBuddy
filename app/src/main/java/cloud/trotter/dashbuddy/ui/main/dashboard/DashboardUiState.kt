package cloud.trotter.dashbuddy.ui.main.dashboard

import androidx.annotation.StringRes
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics

/**
 * Immutable state for the home (Dashboard) screen (Principle 1 — UDF).
 *
 * The dashboard is a **review / configure** surface, not a live-while-dashing mirror of
 * the bubble HUD (#657): the bubble owns the strictly-live glance while on a task; the
 * main app is opened between shifts (parked, on break, planning) to review history and
 * configure. So the primary economics are the read-model [economics] for the selected
 * [selectedPeriod] (Today / This week / Lifetime) — reactive to projector commits (Room
 * invalidation) and midnight/week rollover, but **not** a per-second tick surface: a
 * historical period's $/hr is a fixed computed value, so the screen is fresh-on-open
 * without a `rememberNow()` clock.
 *
 * [isDashing] only drives a slim "tap for the bubble" pointer shown while a dash is
 * active; it never brings back the live glance the bubble already owns.
 *
 * Permissions stay a composable-local OS re-check (re-read on every `ON_RESUME`), so
 * they are intentionally NOT modelled here.
 */
data class DashboardUiState(
    val isFirstRun: Boolean = true,
    /**
     * The home status headline — a `@StringRes` id (#428 Half A), resolved with
     * `stringResource(...)` at the Compose layer so this Context-free `@HiltViewModel` never
     * carries resolved copy. Not i18n: the app still ships English-only; this is a string-
     * ownership move (SSOT), not locale selection.
     */
    @StringRes val statusText: Int = R.string.dashboard_status_offline,
    /**
     * True while a dash session is active (focused region present, mode != Offline),
     * registry-resolved (never a `== DoorDash` literal). Drives the bubble pointer only.
     */
    val isDashing: Boolean = false,
    /** The review window the tiles aggregate over; default [AnalyticsPeriod.TODAY]. */
    val selectedPeriod: AnalyticsPeriod = AnalyticsPeriod.TODAY,
    /** Frozen-net read-model economics for [selectedPeriod]; fresh-on-open, not live-ticking. */
    val economics: PeriodEconomics = PeriodEconomics.EMPTY,
)
