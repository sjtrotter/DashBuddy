package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.domain.census.CensusChoices
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.di.ReadsUnboundPlatforms
import cloud.trotter.log.gig.domain.state.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One delivery app on the census screen, with whether the census covers it. */
data class CensusPlatformRow(val platform: Platform, val checked: Boolean)

/**
 * #1621: the census screen. [consent] is the stored decision bound to the endpoint; [testBuild] picks the
 * build note (the test build's census differs from the Play and sideload builds', #1620).
 */
data class CensusUiState(
    val consent: CensusConsent,
    val platforms: List<CensusPlatformRow>,
    val wifiOnly: Boolean,
    val serverHost: String,
    val testBuild: Boolean,
    /** #1622: the last "Delete my census data" request, as stored; null before the first. */
    val deletion: CensusDeletion? = null,
    /** #1622: a deletion request is being made now. */
    val deleting: Boolean = false,
) {
    val choices: CensusChoices
        get() = CensusChoices(platforms.filter { it.checked }.map { it.platform }.toSet(), wifiOnly)
}

/** #1621: the census opt-in (Settings > Data & privacy > Census). The disclosure's Accept is the only way on. */
@HiltViewModel
class CensusViewModel @Inject constructor(
    private val settings: DevSettingsRepository,
    private val deletion: CensusDataDeletion,
    @ReadsUnboundPlatforms readsUnboundPlatforms: Boolean,
) : ViewModel() {

    private val readable = Platform.readable(readsUnboundPlatforms)

    /** Null until the store answers; the screen shows no switch before then. */
    val uiState: StateFlow<CensusUiState?> =
        combine(
            settings.censusConsent, settings.censusChoices, settings.censusBaseUrl, settings.censusDeletion,
            deletion.inProgress,
        ) { consent, choices, url, request, deleting ->
            val covered = choices.platformsAmong(readable)
            CensusUiState(
                consent = consent,
                platforms = readable.map { CensusPlatformRow(it, it in covered) },
                wifiOnly = choices.wifiOnly,
                serverHost = url.removePrefix("https://"),
                testBuild = settings.censusTestBuild,
                deletion = request,
                deleting = deleting,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The disclosure's Accept: on, with a receipt for this endpoint and the choices on screen, in one edit. */
    fun accept() {
        val state = uiState.value ?: return
        viewModelScope.launch { settings.setCensusUploadEnabled(true, state.choices) }
    }

    /** Not now, or the switch turned off: a recorded decline. */
    fun decline() {
        viewModelScope.launch { settings.declineCensus() }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch { settings.setCensusWifiOnly(wifiOnly) }
    }

    /**
     * #1622: "Delete my census data", confirmed. It runs on the application scope, so leaving the screen never
     * cancels it; the screen shows the stored outcome.
     */
    fun deleteServerData() {
        deletion.deleteAsync()
    }

    /** Changes one platform inside the store's edit, never from this screen's snapshot, so quick taps never overwrite each other. */
    fun setPlatform(platform: Platform, covered: Boolean) {
        viewModelScope.launch { settings.setCensusPlatform(platform, covered, readable) }
    }
}
