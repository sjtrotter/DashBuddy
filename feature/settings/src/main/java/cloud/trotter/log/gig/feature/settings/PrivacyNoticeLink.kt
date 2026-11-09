package cloud.trotter.log.gig.feature.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * #170 — the one "Full privacy notice" link on the screen reading card and Automation & consent. It opens gig.log's
 * own Privacy page ([PrivacyScreen]) through [onClick], a navigation callback its host supplies; the page links the
 * privacy policy and the technical details.
 */
@Composable
fun PrivacyNoticeLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(stringResource(R.string.consent_privacy_link))
    }
}
