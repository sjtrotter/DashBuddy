package cloud.trotter.log.gig.ui.main.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.domain.format.formatDurationMinutes
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import cloud.trotter.log.gig.notice.RecognitionNoticeCopy

/**
 * Home's card for one open recognition incident (#1560): the notice's title and text, then how long it
 * has been open ("For 2h 5m"). Shown whether or not the notice was seen or allowed, so a muted or denied
 * notification still reaches the dasher.
 *
 * The elapsed line derives from the incident's `openedAt` anchor and [rememberNow] at minute grain
 * (Reactive UI rules 2 and 3), so it counts up while the dasher looks at it.
 *
 * Lives in `:app`, not `:feature:dashboard`: its copy has one owner, [RecognitionNoticeCopy], shared with
 * the notifier (principle 5) — the same exception Home's review rows take.
 */
@Composable
internal fun RecognitionIncidentCard(incident: RecognitionIncident, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val now by rememberNow(60_000)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = RecognitionNoticeCopy.title(res, incident), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = RecognitionNoticeCopy.text(res, incident), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.recognition_home_since_format,
                    formatDurationMinutes(now - incident.openedAt),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
