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
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure

/**
 * #1457 — the in-app privacy page (Settings > Data & privacy > Privacy): what gig.log reads, saves
 * and sends, in plain sections. It is where the facts the permission cards stopped repeating live, so
 * each card says only what its own permission does. Static copy, no state; `PRIVACY.md` is the
 * engineering ground truth, and a change to a claim there updates the matching `privacy_*` string in
 * the same PR. The Test builds section shows only where `privacy_show_test_builds` is true (the debug
 * source set).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
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
            PRIVACY_SECTIONS.forEach { (title, body) -> PrivacySection(title, body) }
            if (showTestBuilds) {
                PrivacySection(R.string.privacy_test_builds_title, R.string.privacy_test_builds_body)
            }
            TextButton(
                // No browser (restricted/work profile) throws from openUri; the link must never crash the page.
                onClick = { runCatching { uriHandler.openUri(PrivacyDisclosure.URL) } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.consent_privacy_link))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The page's sections in order (heading, body); the Test builds section is added after them when shown. */
private val PRIVACY_SECTIONS: List<Pair<Int, Int>> = listOf(
    R.string.privacy_reads_title to R.string.privacy_reads_body,
    R.string.privacy_customers_title to R.string.privacy_customers_body,
    R.string.privacy_saves_title to R.string.privacy_saves_body,
    R.string.privacy_leaves_title to R.string.privacy_leaves_body,
    R.string.privacy_screenshots_title to R.string.privacy_screenshots_body,
    R.string.privacy_backup_title to R.string.privacy_backup_body,
    R.string.privacy_retention_title to R.string.privacy_retention_body,
    R.string.privacy_choices_title to R.string.privacy_choices_body,
)

@Composable
private fun PrivacySection(@StringRes title: Int, @StringRes body: Int) {
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
}
