package cloud.trotter.log.gig.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.domain.census.CensusSentBatch
import cloud.trotter.log.gig.domain.census.CensusSentItem
import cloud.trotter.log.gig.domain.census.CensusSentKind
import cloud.trotter.log.gig.domain.census.countBy
import cloud.trotter.log.gig.domain.format.formatClockTime
import cloud.trotter.log.gig.domain.format.formatDurationMinutes
import cloud.trotter.log.gig.domain.format.formatMonthDayYear
import cloud.trotter.log.gig.domain.state.Platform
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** #1622: Settings > Data & privacy > Census > What gig.log sent. */
@Composable
fun CensusSentScreen(onBack: () -> Unit, viewModel: CensusSentViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CensusSentContent(state = state, onBack = onBack)
}

/**
 * The census requests the server read in the last 7 days, by day, each item with its kind, delivery app and counts,
 * never a code or a value. The next upload's time left ticks from the stored target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CensusSentContent(state: CensusSentUiState?, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.census_sent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state == null) return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.census_sent_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.census_sent_last)) },
                    supportingContent = {
                        Text(state.lastUploadAtMillis?.let { dateTime(it) } ?: stringResource(R.string.census_sent_none))
                    },
                )
            }
            item { NextUpload(state) }
            if (state.days.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.census_sent_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            state.days.forEach { day ->
                item(key = "day-${day.date}") { DayHeader(day) }
                day.batches.forEach { batch ->
                    item(key = "batch-${batch.sentAtMillis}-${batch.path}-${batch.batchId}") { BatchHeader(batch) }
                    items(batch.items) { SentItemRow(it) }
                }
            }
        }
    }
}

/** The stored target of the next daily run and the time left to it, ticking; "due" once it has passed. */
@Composable
private fun NextUpload(state: CensusSentUiState) {
    val next = state.nextUploadAtMillis
    val now by rememberNow()
    val value = when {
        !state.censusOn -> stringResource(R.string.census_sent_next_off)
        next == null -> stringResource(R.string.census_sent_next_unset)
        now < next -> stringResource(R.string.census_sent_next_in, dateTime(next), formatDurationMinutes(next - now))
        else -> stringResource(R.string.census_sent_next_due, dateTime(next))
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.census_sent_next)) },
        supportingContent = { Text(value) },
    )
}

@Composable
private fun DayHeader(day: CensusSentDay) {
    val text = day.batches.countBy(CensusSentKind.SCREEN_TEXT)
    val summary = if (text > 0) {
        stringResource(R.string.census_sent_day_summary_text, day.batches.countBy(CensusSentKind.SCREEN),
            day.batches.countBy(CensusSentKind.NOTIFICATION), day.batches.countBy(CensusSentKind.DAILY_COUNTS), text)
    } else {
        stringResource(R.string.census_sent_day_summary, day.batches.countBy(CensusSentKind.SCREEN),
            day.batches.countBy(CensusSentKind.NOTIFICATION), day.batches.countBy(CensusSentKind.DAILY_COUNTS))
    }
    ListItem(
        headlineContent = {
            Text(
                formatMonthDayYear(day.date),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        },
        supportingContent = { Text(summary) },
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun BatchHeader(batch: CensusSentBatch) {
    Text(
        stringResource(R.string.census_sent_batch, formatClockTime(batch.sentAtMillis), batch.items.size),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SentItemRow(item: CensusSentItem) {
    val app = Platform.fromWire(item.platform)?.displayName ?: item.platform
    val (title, detail) = when (item.kind) {
        CensusSentKind.SCREEN -> stringResource(R.string.census_sent_item_screen, app) to stringResource(
            R.string.census_sent_values_screen, item.nodes, item.codes, item.formats, item.typeOnly, item.withheld,
        )
        CensusSentKind.NOTIFICATION -> stringResource(R.string.census_sent_item_notification, app) to stringResource(
            R.string.census_sent_values_notification, item.codes, item.formats, item.typeOnly, item.withheld,
        )
        CensusSentKind.DAILY_COUNTS -> stringResource(R.string.census_sent_item_counts, app) to (
            item.day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.let { stringResource(R.string.census_sent_counts_day, formatMonthDayYear(it)) }
            )
        CensusSentKind.SCREEN_TEXT -> stringResource(R.string.census_sent_item_text, app) to
            stringResource(R.string.census_sent_text_supporting)
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
    )
}

/** "Nov 14, 2026, 2:10 AM" in the phone's zone and locale. */
@Composable
private fun dateTime(millis: Long): String = stringResource(
    R.string.census_sent_date_time,
    formatMonthDayYear(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()),
    formatClockTime(millis),
)
