package cloud.trotter.log.gig.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme

/** The one line under the title bar while the records run behind the event log. */
@Composable
internal fun UpdatingRecordsLine(modifier: Modifier = Modifier) {
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
