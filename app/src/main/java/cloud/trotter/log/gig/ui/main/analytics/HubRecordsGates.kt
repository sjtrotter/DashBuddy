package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.RecordsStatus

/**
 * True when the hub may show figures beyond its own body and offer correction actions: its reads
 * loaded and did not fail, and the records are not rebuilding. The range picker's dots and the
 * correction dialogs both follow this one rule.
 */
internal fun AnalyticsUiState.figuresShown(): Boolean =
    loaded && !failed && records != RecordsStatus.REBUILDING

/** The range picker's earning-dot mode for the hub's [state] and the month read [days]. */
internal fun pickerMarks(state: AnalyticsUiState, days: PickerMonthDays): PickerMarks = when {
    !state.figuresShown() -> PickerMarks.HELD_BACK
    days.failed -> PickerMarks.FAILED
    else -> PickerMarks.SHOWN
}
