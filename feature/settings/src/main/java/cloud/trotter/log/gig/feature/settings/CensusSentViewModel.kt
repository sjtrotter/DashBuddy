package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.census.CensusSentLog
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.census.CensusSentBatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** #1622: the sent requests of one local day, newest first. */
data class CensusSentDay(val date: LocalDate, val batches: List<CensusSentBatch>)

/**
 * #1622: "What gig.log sent". [days] come from the phone's sent record, which the transport fills from each request's
 * own bytes. [nextUploadAtMillis] is the stored target of the next daily run (the anchor; the screen ticks the time
 * left), null while the census is off or before the first draw.
 */
data class CensusSentUiState(
    val days: List<CensusSentDay>,
    val censusOn: Boolean,
    val nextUploadAtMillis: Long?,
) {
    /** The newest request the server read, or null when none is kept. */
    val lastUploadAtMillis: Long? get() = days.firstOrNull()?.batches?.firstOrNull()?.sentAtMillis
}

/** Groups [batches] by the local day they were sent, newest day and newest request first. */
fun groupSentByDay(batches: List<CensusSentBatch>, zone: ZoneId): List<CensusSentDay> =
    batches.sortedByDescending { it.sentAtMillis }
        .groupBy { Instant.ofEpochMilli(it.sentAtMillis).atZone(zone).toLocalDate() }
        .map { (date, sent) -> CensusSentDay(date, sent) }

@HiltViewModel
class CensusSentViewModel @Inject constructor(
    sentLog: CensusSentLog,
    settings: DevSettingsRepository,
) : ViewModel() {
    /** Null until the record and the store answer. */
    val uiState: StateFlow<CensusSentUiState?> =
        combine(sentLog.batches, settings.censusConsent, settings.censusNextRunAtMillis) { batches, consent, next ->
            val on = consent == CensusConsent.ON
            CensusSentUiState(groupSentByDay(batches, ZoneId.systemDefault()), on, next.takeIf { on })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
