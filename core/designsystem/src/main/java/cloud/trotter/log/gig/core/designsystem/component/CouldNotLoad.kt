package cloud.trotter.log.gig.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme

/**
 * The one failure pattern for a read that failed: "Couldn't load [what]" and a Retry button, in
 * place of the figures. Words and controls that need no records stay on the screen around it.
 */
@Composable
fun CouldNotLoad(what: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.app_could_not_load, what),
            style = MaterialTheme.typography.titleMedium,
            color = AppTheme.colors.text,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.app_retry)) }
    }
}
