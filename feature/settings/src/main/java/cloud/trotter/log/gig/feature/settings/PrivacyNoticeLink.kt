package cloud.trotter.log.gig.feature.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource

/**
 * #170 — the one "Full privacy notice" link: the screen reading card, Automation & consent and the privacy page
 * all show this. [url] is the `@PrivacyNoticeUrl` value, PRIVACY.md as of the build's commit.
 */
@Composable
fun PrivacyNoticeLink(url: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    TextButton(
        // No browser (restricted/work profile) throws from openUri; a privacy link must never crash its screen.
        onClick = { runCatching { uriHandler.openUri(url) } },
        modifier = modifier,
    ) {
        Text(stringResource(R.string.consent_privacy_link))
    }
}
