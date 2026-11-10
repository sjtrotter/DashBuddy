package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Writes Recap's week (#1515). The persisted [AnalyticsWindowSelection] is the one owner of the week Recap shows: the
 * period bar's arrows, the date picker's Week and the Month screen's rows all write it through here, and Recap reads it
 * back, so the period bar and the picker never disagree. A failed write is logged and dropped (the week stays put).
 */
internal suspend fun AppPreferencesRepository.writeRecapWeek(selection: AnalyticsWindowSelection, tag: String) {
    try {
        setAnalyticsWindow(selection)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // P7: no PII, an offset only.
        Timber.tag(tag).w(e, "week selection not persisted")
    }
}
