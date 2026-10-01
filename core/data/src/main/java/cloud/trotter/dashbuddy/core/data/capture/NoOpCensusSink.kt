package cloud.trotter.dashbuddy.core.data.capture

import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.capture.CensusSink
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Census publishing is structurally disabled in every variant until M2/M3 (#1146). */
@Singleton
class NoOpCensusSink @Inject constructor() : CensusSink {

    init {
        Timber.tag("Census").i("Census publishing disabled (no sink bound) — skeletons are not built")
    }

    override val isEnabled: Boolean = false

    override fun offer(record: CensusRecord): Boolean = false
}
