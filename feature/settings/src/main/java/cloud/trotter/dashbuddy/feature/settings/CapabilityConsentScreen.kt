package cloud.trotter.dashbuddy.feature.settings

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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import cloud.trotter.dashbuddy.domain.capability.PrivacyDisclosure
import cloud.trotter.dashbuddy.domain.format.formatShortDate
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.ZoneId
import java.util.Locale

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
    val uriHandler = LocalUriHandler.current

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
            EventReceiptSection(
                allowed = uiState.eventReceiptAllowed,
                receipt = uiState.eventReceipt,
                onAllowedChange = viewModel::setEventReceiptAllowed,
            )
            Spacer(Modifier.height(16.dp))
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
                        receipts = uiState.receipts,
                        onSetGranted = viewModel::setGranted,
                    )
                }
            }

            TextButton(
                // No browser (restricted/work profile) throws from openUri — a privacy link must never crash the record screen.
                onClick = { runCatching { uriHandler.openUri(PrivacyDisclosure.URL) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.consent_privacy_link))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * #1151 — "Screen events": the wide-event-receipt FEATURE consent's record. The switch writes
 * through [CapabilityConsentViewModel.setEventReceiptAllowed]; the listener enforces it.
 */
@Composable
private fun EventReceiptSection(
    allowed: Boolean,
    receipt: ConsentReceipt?,
    onAllowedChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.event_receipt_settings_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(
                    label = stringResource(R.string.event_receipt_settings_switch),
                    subtitle = eventReceiptSettingsHelper(),
                    checked = allowed,
                    onCheckedChange = onAllowedChange,
                )
                receipt?.let { ConsentReceiptCaption(it) }
            }
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
    receipts: Map<String, ConsentReceipt>,
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
                    ConsentCapabilityRowView(
                        row = cap,
                        receipt = receipts[cap.key],
                        platformName = platformName,
                        onSetGranted = onSetGranted,
                    )
                }
            }
        }
    }
}

/** The consent switch row, with distinct layout coverage directly below its title (#1167). */
@Composable
private fun ConsentCapabilityRowView(
    row: ConsentCapabilityRow,
    receipt: ConsentReceipt?,
    platformName: String,
    onSetGranted: (key: String, granted: Boolean) -> Unit,
) {
    val copy = capabilityCopy(row.action, platformName)
    SwitchRow(
        label = copy.title,
        subtitle = copy.description,
        note = if (row.layoutCount > 1) stringResource(R.string.consent_layout_count_format, row.layoutCount) else null,
        checked = row.granted,
        onCheckedChange = { onSetGranted(row.key, it) },
    )
    receipt?.let { ConsentReceiptCaption(it, Modifier.padding(horizontal = 16.dp)) }
}

@Composable
private fun ConsentReceiptCaption(receipt: ConsentReceipt, modifier: Modifier = Modifier) {
    Text(
        text = formatConsentReceipt(
            receipt = receipt,
            format = stringResource(R.string.consent_receipt_format),
            allowed = stringResource(R.string.consent_receipt_allowed),
            denied = stringResource(R.string.consent_receipt_denied),
            locale = LocalConfiguration.current.locales[0],
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(bottom = 12.dp),
    )
}

/** Pure receipt caption; date formatting shares the app's localized calendar-date policy. */
internal fun formatConsentReceipt(
    receipt: ConsentReceipt,
    format: String,
    allowed: String,
    denied: String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = String.format(
    locale,
    format,
    if (receipt.granted) allowed else denied,
    formatShortDate(receipt.decidedAt, zone, locale),
    receipt.appVersion,
    receipt.disclosureRevision,
)
