package cloud.trotter.log.gig.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure

/**
 * #1457 — the in-app privacy page (Settings > Data & privacy > Privacy): what gig.log reads, saves
 * and sends, in plain sections. It is where the facts the permission cards stopped repeating live, so
 * each card says only what its own permission does. Static copy, no state; `PRIVACY.md` is the
 * engineering ground truth, and a change to a claim there updates the matching `privacy_*` string in
 * the same PR. The Test builds section shows only where `privacy_show_test_builds` is true (the debug
 * source set). #170: the Screen events line shows only in a build that offers Screen events. The foot links the
 * privacy policy ([PrivacyDisclosure.POLICY_URL]) and the technical details, PRIVACY.md as of this build's commit
 * ([PrivacyViewModel]). The screen reading card and Automation & consent link here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit, viewModel: PrivacyViewModel = hiltViewModel()) {
    val showTestBuilds = booleanResource(R.bool.privacy_show_test_builds)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_title)) },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            PRIVACY_SECTIONS.forEach { (title, body) ->
                val extra = R.string.privacy_reads_screen_events.takeIf {
                    title == R.string.privacy_reads_title && viewModel.screenEventsOffered
                }
                PrivacySection(title, body, extra)
            }
            if (showTestBuilds) {
                PrivacySection(R.string.privacy_test_builds_title, R.string.privacy_test_builds_body)
            }
            // #170: the page's foot links the plain-language policy and the engineering document, PRIVACY.md as of this
            // build's commit. The card's and Automation & consent's privacy links open this page.
            WebLink(R.string.privacy_policy_link, PrivacyDisclosure.POLICY_URL, Modifier.fillMaxWidth().padding(top = 8.dp))
            WebLink(R.string.privacy_technical_link, viewModel.privacyUrl, Modifier.fillMaxWidth())
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The page's sections in order (heading, body); the Test builds section is added after them when shown. The web
 * privacy policy (tools/site/build_site.py, #1582) takes the same sections in strings.xml file order, so this list
 * follows that order; PrivacySectionOrderTest pins it.
 */
internal val PRIVACY_SECTIONS: List<Pair<Int, Int>> = listOf(
    R.string.privacy_reads_title to R.string.privacy_reads_body,
    R.string.privacy_customers_title to R.string.privacy_customers_body,
    R.string.privacy_saves_title to R.string.privacy_saves_body,
    R.string.privacy_leaves_title to R.string.privacy_leaves_body,
    R.string.privacy_screenshots_title to R.string.privacy_screenshots_body,
    R.string.privacy_backup_title to R.string.privacy_backup_body,
    R.string.privacy_retention_title to R.string.privacy_retention_body,
    R.string.privacy_choices_title to R.string.privacy_choices_body,
)

/** A link that opens [url] in the browser. No browser (restricted or work profile) throws; it must never crash the page. */
@Composable
private fun WebLink(@StringRes label: Int, url: String, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }, modifier = modifier) {
        Text(stringResource(label))
    }
}

/** One section: its heading, its body and, when given, a second paragraph ([extra]). */
@Composable
private fun PrivacySection(@StringRes title: Int, @StringRes body: Int, @StringRes extra: Int? = null) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (extra != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(extra),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
