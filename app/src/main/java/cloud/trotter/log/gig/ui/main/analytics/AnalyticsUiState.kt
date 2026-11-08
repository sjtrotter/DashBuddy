package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.ANALYTICS_MONEY_EPSILON
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindow
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.PeriodEconomics
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.recap.DayMark
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.recap.RecapComparison
import cloud.trotter.log.gig.domain.analytics.recap.RecapDay
import cloud.trotter.log.gig.domain.analytics.recap.RecapMeasure
import java.time.LocalDate

/**
 * Below this a money figure is effectively zero: no callout, no cash line. The `:domain` [ANALYTICS_MONEY_EPSILON]
 * under the name the analytics screens use.
 */
internal const val UNATTRIBUTED_EPSILON = ANALYTICS_MONEY_EPSILON

/**
 * Immutable state for Recap, the week (#1515; Principle 1, UDF). A review surface: every figure is the read model
 * for [week], re-emitting on each projector commit and re-anchoring at local midnight, with no per-second tick.
 */
data class AnalyticsUiState(
    /**
     * False only for the ViewModel's placeholder before the first read emits (#1447): the screen composes its
     * scrolling column only once loaded, so a restored scroll offset is never clamped against the placeholder.
     */
    val loaded: Boolean = true,
    /** A read failed: the screen shows the failure pattern and Retry instead of figures. */
    val failed: Boolean = false,
    /** Where the records stand against the event log; REBUILDING holds every figure back. */
    val records: RecordsStatus = RecordsStatus.UP_TO_DATE,
    /** The Monday week shown; the persisted [AnalyticsWindowSelection] owns which. */
    val week: AnalyticsWindow = AnalyticsWindowSelection.DEFAULT.resolve(LocalDate.now()),
    /** The device's local date: the anchor of "this week" and of every day's [RecapDay.time]. */
    val today: LocalDate = LocalDate.now(),
    /** `›` is live only off the current week. */
    val canStepForward: Boolean = false,
    /** The Earned / Est. Profit switch. */
    val measure: RecapMeasure = RecapMeasure.EARNED,
    /** False until any shift has been recorded: the "no shifts yet" state. */
    val anyShift: Boolean = true,
    /** True while a shift runs; the comparison waits. */
    val shiftRunning: Boolean = false,
    val economics: PeriodEconomics = PeriodEconomics.EMPTY,
    val lastWeekEconomics: PeriodEconomics = PeriodEconomics.EMPTY,
    /** Monday to Sunday of [week], one mark each. */
    val days: List<RecapDay> = emptyList(),
    /** The same seven days of the week before; their values are the chart's dots. */
    val lastWeekDays: List<RecapDay> = emptyList(),
    /** Null on a Monday of the current week and while a shift runs. */
    val comparison: RecapComparison? = null,
    /** The week's ended shifts that need a check, oldest first; `ShiftChecks` judges each. */
    val shiftsToCheck: List<ShiftToCheck> = emptyList(),
    /** The week's expenses plus fuel. */
    val spending: Double = 0.0,
    /** The week's deliveries with no shift, for the assign dialog. */
    val noSessionDeliveries: List<DeliveryRecord> = emptyList(),
) {
    /** Shifts that started in [week]. */
    val shiftCount: Int get() = days.sumOf { it.shifts.size }

    /** No shift and no delivery this week, with shifts recorded before. */
    val weekOff: Boolean
        get() = anyShift && days.all { it.mark == DayMark.NO_SHIFT || it.mark == DayMark.AHEAD }

    /** The week's total for the switch. */
    val total: Double get() = economics.valueOf(measure)

    val lastWeekTotal: Double get() = lastWeekEconomics.valueOf(measure)
}

/** Earned (gross) or Est. Profit (frozen net) of a period, for the switch. */
internal fun PeriodEconomics.valueOf(measure: RecapMeasure): Double = when (measure) {
    RecapMeasure.EARNED -> grossEarnings
    RecapMeasure.PROFIT -> netProfit
}

/** One ended shift of the week, the local day it started, and its checks. */
data class ShiftToCheck(val date: LocalDate, val shift: DayShift, val checks: List<ShiftCheck>)
