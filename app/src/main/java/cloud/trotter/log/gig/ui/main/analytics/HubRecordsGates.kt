package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.RecordsStatus

/**
 * True when Recap may show figures and offer correction actions: its reads loaded and did not fail, and the records
 * are not rebuilding. The week's body and the assign dialog both follow this one rule.
 */
internal fun AnalyticsUiState.figuresShown(): Boolean =
    loaded && !failed && records != RecordsStatus.REBUILDING
