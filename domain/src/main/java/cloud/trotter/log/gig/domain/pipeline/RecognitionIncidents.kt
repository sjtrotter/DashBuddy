package cloud.trotter.log.gig.domain.pipeline

import kotlinx.coroutines.flow.StateFlow

/**
 * Open recognition incidents, one per platform at most (#1560); sorted by platform wire. In memory only.
 * The read side Home injects; `RecognitionHealthMonitor` (`:core:pipeline`) owns it.
 */
interface RecognitionIncidents {
    val open: StateFlow<List<RecognitionIncident>>
}
