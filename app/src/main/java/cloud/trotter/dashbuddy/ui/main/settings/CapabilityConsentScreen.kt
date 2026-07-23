package cloud.trotter.dashbuddy.ui.main.settings

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * Capability-consent surface (#422 PR 3): a Google-Play-consistent
 * prominent-disclosure header plus, per ruleset source, one row per automation
 * tap the rules enable — each with honest disclosure copy and a grant/revoke
 * switch. The switch writes THROUGH [CapabilityConsentViewModel.setGranted] into
 * the same grant store the fail-closed engine gate (#417) reads at fire time;
 * revoking a capability makes the next automation tap abort to manual.
 *
 * All copy is app-owned string resources keyed off the [RuleAction] vocabulary,
 * never rule-supplied text (see `docs/design/rule-capability-consent.md`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilityConsentScreen(
    onBack: () -> Unit,
    viewModel: CapabilityConsentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.consent_title)) },
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
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            DisclosureHeader()

            if (uiState.sources.isEmpty()) {
                Text(
                    text = stringResource(R.string.consent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                uiState.sources.forEach { group ->
                    Spacer(Modifier.height(16.dp))
                    ConsentSourceSection(
                        group = group,
                        onSetGranted = viewModel::setGranted,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The prominent, Play-consistent disclosure of the Accessibility usage overall. */
@Composable
private fun DisclosureHeader() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.consent_disclosure_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.consent_disclosure_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConsentSourceSection(
    group: ConsentSourceGroup,
    onSetGranted: (key: String, granted: Boolean) -> Unit,
) {
    val platformName = group.platform
        .takeIf { it != Platform.Unknown }
        ?.displayName
        ?: stringResource(R.string.consent_platform_unknown)

    val header = if (group.isBundled) {
        stringResource(R.string.consent_source_bundled_format, platformName)
    } else {
        stringResource(R.string.consent_source_downloaded_format, platformName)
    }
    val note = if (group.isBundled) {
        stringResource(R.string.consent_source_bundled_note)
    } else {
        stringResource(R.string.consent_source_downloaded_note)
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = header,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
        ) {
            Column {
                group.capabilities.forEach { cap ->
                    val copy = capabilityCopy(cap.action, platformName)
                    SwitchRow(
                        label = copy.title,
                        subtitle = copy.description,
                        checked = cap.granted,
                        onCheckedChange = { onSetGranted(cap.key, it) },
                    )
                }
            }
        }
    }
}
