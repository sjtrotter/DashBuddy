package cloud.trotter.log.gig.ui.main.analytics.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.analytics.StoreSearchRepository
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.search.ShiftMatches
import cloud.trotter.log.gig.domain.analytics.search.StoreQuery
import cloud.trotter.log.gig.ui.main.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject

/** The Search screen's results (#1540). The query text itself is [SearchViewModel.query]. */
data class SearchUiState(
    /** The normalized query [groups] answer; null before the first result and while the query is blank. */
    val resultsFor: String? = null,
    /** One group per matched shift, newest first: this page's shifts. */
    val groups: List<ShiftMatches> = emptyList(),
    /** True when more matched shifts follow this page. */
    val hasMore: Boolean = false,
    val failed: Boolean = false,
    val records: RecordsStatus = RecordsStatus.UP_TO_DATE,
)

/** How many matched shifts a page adds. */
internal const val SEARCH_PAGE_SHIFTS = 10

/** How long the results wait after the last keystroke. */
internal const val SEARCH_DEBOUNCE_MS = 250L

/**
 * State holder for the Search screen: every shift, searched by store ([StoreSearchRepository]). The query and the
 * page count live in [SavedStateHandle] (the query under the route's own key, so the "Search all shifts for …" link
 * seeds it), so Back from a result and process death keep both. Results follow the query after [SEARCH_DEBOUNCE_MS];
 * a page folds [SEARCH_PAGE_SHIFTS] more matched shifts. `WhileSubscribed` keeps the last results, and nothing
 * emits a loading state on resubscribe, so the list never blanks on return. A failed read is one WARN with the
 * failure class only. The query is a merchant name and is never logged.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val storeSearchRepository: StoreSearchRepository,
    private val recordsStatusRepository: RecordsStatusRepository,
) : ViewModel() {

    /** The field's text: snapshot state, so typing never waits on a flow. */
    var query: String by mutableStateOf(savedStateHandle.get<String>(Screen.Search.ARG_QUERY).orEmpty())
        private set

    private val attempts = MutableStateFlow(0)

    /** UDF intent: the field changed. A new query starts again at the first page. */
    fun onQueryChange(text: String) {
        if (StoreQuery.normalize(text) != StoreQuery.normalize(query)) savedStateHandle[KEY_PAGES] = 1
        query = text
        savedStateHandle[Screen.Search.ARG_QUERY] = text
    }

    /** UDF intent: "Show more shifts". */
    fun showMore() {
        savedStateHandle[KEY_PAGES] = (savedStateHandle.get<Int>(KEY_PAGES) ?: 1) + 1
    }

    /** UDF intent: the failure pattern's Retry. */
    fun retry() {
        attempts.value++
    }

    val uiState: StateFlow<SearchUiState> = attempts.flatMapLatest { attempt ->
        savedStateHandle.getStateFlow<String?>(Screen.Search.ARG_QUERY, null)
            .map { StoreQuery.of(it.orEmpty()) }
            .distinctUntilChanged()
            .debounce { if (it == null) 0L else SEARCH_DEBOUNCE_MS }
            .flatMapLatest { search -> if (search == null) flowOf(SearchUiState()) else results(search) }
            // Only a Retry from the failed state starts blank; a resubscribe after WhileSubscribed lapses (Back from a
            // result) keeps the last results, so the list keeps its position.
            .onStart { if (attempt > 0 && uiState.value.failed) emit(SearchUiState()) }
            .catch { e -> emit(failedRead(e)) }
            .combine(recordsStatusRepository.status) { state, records -> state.copy(records = records) }
            .catch { e -> emit(failedRead(e)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchUiState())

    private fun results(search: StoreQuery): Flow<SearchUiState> =
        storeSearchRepository.matchingShifts(search).flatMapLatest { shifts ->
            savedStateHandle.getStateFlow(KEY_PAGES, 1).flatMapLatest { pages ->
                val page = shifts.take(pages.coerceAtLeast(1) * SEARCH_PAGE_SHIFTS)
                storeSearchRepository.results(page, search).map { groups ->
                    SearchUiState(resultsFor = search.normalized, groups = groups, hasMore = shifts.size > page.size)
                }
            }
        }

    private fun failedRead(e: Throwable): SearchUiState {
        if (e is CancellationException) throw e
        // P7: the failure class only. Never the message, which could carry the query.
        Timber.tag(TAG).w("store search read failed: %s", e.javaClass.simpleName)
        return SearchUiState(failed = true)
    }

    private companion object {
        const val TAG = "SearchVm"
        const val KEY_PAGES = "search_pages"
    }
}
