package cloud.trotter.log.gig.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import kotlinx.coroutines.delay

/** How long the records must stay behind before the Updating line shows: an ordinary fold is quicker. */
internal const val RECORDS_UPDATING_LINE_DELAY_MS = 2_000L

/**
 * The one line under the title bar while the records run behind the event log. [updating] follows
 * `RecordsStatus` immediately; the line appears only once it has held for
 * [RECORDS_UPDATING_LINE_DELAY_MS] (on the composition's clock), so a brief projection lag never
 * shifts the content, and it goes as soon as the records catch up.
 */
@Composable
internal fun UpdatingRecordsLine(updating: Boolean, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(updating) {
        if (updating) {
            delay(RECORDS_UPDATING_LINE_DELAY_MS)
            shown = true
        } else {
            shown = false
        }
    }
    if (!shown) return
    Text(
        stringResource(R.string.records_updating),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text2,
    )
}

/**
 * Shown in place of every total, comparison and correction action while the records rebuild: a
 * half-rebuilt period would read as $0.00 or as a period with nothing recorded.
 */
@Composable
internal fun RebuildingRecords(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.records_rebuilding_title),
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.text,
        )
        Text(
            stringResource(R.string.records_rebuilding_body),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.text2,
        )
    }
}
