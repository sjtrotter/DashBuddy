package cloud.trotter.log.gig.ui.main.analytics.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.CouldNotLoad
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.search.SearchRow
import cloud.trotter.log.gig.domain.analytics.search.ShiftMatches
import cloud.trotter.log.gig.domain.analytics.search.StoreQuery
import cloud.trotter.log.gig.domain.format.formatWeekdayMonthDay
import cloud.trotter.log.gig.ui.components.RebuildingRecords
import cloud.trotter.log.gig.ui.components.UpdatingRecordsLine
import cloud.trotter.log.gig.ui.main.analytics.JobCardRow
import cloud.trotter.log.gig.ui.main.analytics.PassedOfferRow
import cloud.trotter.log.gig.ui.main.analytics.shiftAppTimesText
import java.time.Instant
import java.time.ZoneId

/**
 * Search all shifts by store (#1540). Title bar: Back, then the search field ("Search all shifts"). Once the query has
 * text, "All shifts" sits above the results: one group per matched shift, newest first, headed by its date, app and
 * times, with the matching rows drawn as the shift page draws them. A job opens the shift page with its sheet up, a
 * declined or expired offer opens it scrolled to that row, and a header opens it at the top. "Show more shifts" adds
 * the next page. No ticker: a running shift's header reads "since".
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSession: (sessionId: String, row: String?) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SearchContent(
        query = viewModel.query,
        uiState = uiState,
        onQueryChange = viewModel::setQuery,
        onBack = onBack,
        onOpenSession = onOpenSession,
        onShowMore = viewModel::showMore,
        onRetry = viewModel::retry,
    )
}

/** The Search screen's stateless body; tests and screenshots compose this. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchContent(
    query: String,
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSession: (String, String?) -> Unit,
    onShowMore: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { SearchField(query, onQueryChange, stringResource(R.string.search_all_shifts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_content_desc_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            UpdatingRecordsLine(updating = uiState.records == RecordsStatus.UPDATING && !uiState.failed)
            val search = StoreQuery.normalize(query)
            when {
                uiState.failed -> CouldNotLoad(
                    what = stringResource(R.string.search_could_not_load_what),
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                uiState.records == RecordsStatus.REBUILDING -> RebuildingRecords(Modifier.padding(16.dp))
                // An empty query lists nothing.
                search == null -> Unit
                else -> SearchResults(query, search, uiState, onOpenSession, onShowMore)
            }
        }
    }
}

/** One item of the results list; [key] is unique across groups (a job id need not be unique across shifts). */
private sealed interface ResultItem {
    val key: String

    data object Scope : ResultItem { override val key = "scope" }
    data object NoMatch : ResultItem { override val key = "none" }
    data object More : ResultItem { override val key = "more" }
    data class Header(val shift: DayShift) : ResultItem { override val key get() = "shift:${shift.sessionId}" }
    data class Row(val shift: DayShift, val row: SearchRow) : ResultItem {
        override val key get() = "${shift.sessionId}/${row.key}"
    }
}

/**
 * The scope label, then the groups. The no-match line waits for the current query's own answer: while a newer query
 * loads, the previous answer stays in view rather than a "no match" that is not yet known.
 */
private fun resultItems(search: String, state: SearchUiState): List<ResultItem> = buildList {
    add(ResultItem.Scope)
    if (state.resultsFor == search && state.groups.isEmpty()) add(ResultItem.NoMatch)
    for (group in state.groups) addGroup(group)
    if (state.hasMore) add(ResultItem.More)
}

private fun MutableList<ResultItem>.addGroup(group: ShiftMatches) {
    add(ResultItem.Header(group.shift))
    group.rows.forEach { add(ResultItem.Row(group.shift, it)) }
}

@Composable
private fun SearchResults(
    query: String,
    search: String,
    state: SearchUiState,
    onOpenSession: (String, String?) -> Unit,
    onShowMore: () -> Unit,
) {
    val entries = remember(search, state) { resultItems(search, state) }
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize().testTag(SEARCH_RESULTS_TAG),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.key }, contentType = { it::class }) { item ->
            when (item) {
                ResultItem.Scope -> SearchScopeLabel(stringResource(R.string.search_scope_all))
                ResultItem.NoMatch -> SearchNoMatch(query)
                ResultItem.More -> TextButton(onClick = onShowMore) { Text(stringResource(R.string.search_show_more)) }
                is ResultItem.Header -> GroupHeader(item.shift) { onOpenSession(item.shift.sessionId, null) }
                is ResultItem.Row -> when (val row = item.row) {
                    is SearchRow.Job -> JobCardRow(row.card, onOpen = { onOpenSession(item.shift.sessionId, row.key) })
                    is SearchRow.Passed -> Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { onOpenSession(item.shift.sessionId, row.key) },
                        contentAlignment = Alignment.CenterStart,
                    ) { PassedOfferRow(row.offer) }
                }
            }
        }
    }
}

/** "Wed, Oct 28 · DoorDash, 4:58 PM to 11:04 PM": the shift page's own date and times wording. Opens the shift. */
@Composable
private fun GroupHeader(shift: DayShift, onOpen: () -> Unit) {
    val day = formatWeekdayMonthDay(Instant.ofEpochMilli(shift.startedAt).atZone(ZoneId.systemDefault()).toLocalDate())
    val times = shiftAppTimesText(shift.platform.displayName, shift.startedAt, shift.endedAt)
    Text(
        stringResource(R.string.search_group_header_format, day, times),
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { heading() }
            .padding(top = 16.dp, bottom = 4.dp)
            .testTag("search-group-${shift.sessionId}"),
        style = MaterialTheme.typography.titleSmall,
        color = AppTheme.colors.text,
    )
}

/** The results list's test tag. */
internal const val SEARCH_RESULTS_TAG = "search-results"
