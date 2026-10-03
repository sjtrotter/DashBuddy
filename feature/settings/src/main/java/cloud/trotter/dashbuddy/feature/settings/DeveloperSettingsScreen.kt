package cloud.trotter.dashbuddy.feature.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.domain.model.bubble.BubbleSessionMode
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.feature.settings.R

/**
 * The label + one-line explainer for each bubble session-presentation mode (#867). The enum is the
 * SSOT for the modes themselves; only the copy mapping lives here (a UI concern), mirroring the
 * `TtsLangOption` pattern on General settings.
 */
private val BubbleSessionMode.labelRes: Int
    get() = when (this) {
        BubbleSessionMode.FOLLOW_ACTIVE -> R.string.developer_settings_bubble_session_follow_label
        BubbleSessionMode.PINNED -> R.string.developer_settings_bubble_session_pinned_label
        BubbleSessionMode.MERGED -> R.string.developer_settings_bubble_session_merged_label
    }

private val BubbleSessionMode.explainerRes: Int
    get() = when (this) {
        BubbleSessionMode.FOLLOW_ACTIVE -> R.string.developer_settings_bubble_session_follow_explainer
        BubbleSessionMode.PINNED -> R.string.developer_settings_bubble_session_pinned_explainer
        BubbleSessionMode.MERGED -> R.string.developer_settings_bubble_session_merged_explainer
    }

/**
 * Developer Options — the bubble session experiment (#867) and opt-in debug census uploads (#1182/#1185).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsMenuViewModel = hiltViewModel(),
) {
    val sessionMode by viewModel.bubbleSessionMode
        .collectAsStateWithLifecycle(initialValue = BubbleSessionMode.Default)

    val censusEnabled by viewModel.censusUploadEnabled.collectAsStateWithLifecycle(initialValue = false)
    val censusHost by viewModel.censusHost.collectAsStateWithLifecycle(initialValue = "")
    val censusPrefix by viewModel.censusInstallIdPrefix.collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.developer_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                stringResource(R.string.developer_settings_section_bubble),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Text(
                stringResource(R.string.developer_settings_bubble_session_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.developer_settings_bubble_session_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // Pair each mode with its resolved label so selection maps back to the enum without
            // re-matching localized text through a Context (the #428 General-settings pattern).
            val labeled = BubbleSessionMode.entries.map { it to stringResource(it.labelRes) }
            AppSegmented(
                options = labeled.map { it.second },
                selected = labeled.first { it.first == sessionMode }.second,
                onSelect = { label ->
                    viewModel.setBubbleSessionMode(labeled.first { it.second == label }.first)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(sessionMode.explainerRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            if (viewModel.censusAvailable) {
                val installId by viewModel.censusInstallId.collectAsStateWithLifecycle(initialValue = null)
                val lastRun by viewModel.censusLastRun.collectAsStateWithLifecycle(initialValue = null)
                val queued by viewModel.censusQueued.collectAsStateWithLifecycle(initialValue = 0)
                val now by rememberNow()
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                var showReset by remember { mutableStateOf(false) }
                Text(stringResource(R.string.developer_settings_census_upload))
                Switch(checked = censusEnabled, onCheckedChange = { viewModel.setCensusUploadEnabled(it) })
                Text(
                    stringResource(
                        R.string.developer_settings_census_identity,
                        censusHost,
                        censusPrefix ?: stringResource(R.string.developer_settings_census_not_enrolled),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    lastRun?.let { run ->
                        stringResource(
                            R.string.developer_settings_census_status,
                            formatDuration((now - run.atMillis).coerceAtLeast(0)),
                            run.token(),
                            queued,
                        )
                    } ?: stringResource(R.string.developer_settings_census_status_never, queued),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::uploadCensusNow, enabled = censusEnabled) {
                        Text(stringResource(R.string.developer_settings_census_upload_now))
                    }
                    OutlinedButton(
                        onClick = {
                            installId?.let { id ->
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("DashBuddy census install id", id)))
                                }
                            }
                        },
                        enabled = installId != null,
                    ) {
                        Text(stringResource(R.string.developer_settings_census_copy_id))
                    }
                }
                TextButton(
                    onClick = { showReset = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.developer_settings_census_reset))
                }
                if (showReset) {
                    AlertDialog(
                        onDismissRequest = { showReset = false },
                        title = { Text(stringResource(R.string.developer_settings_census_reset_title)) },
                        text = { Text(stringResource(R.string.developer_settings_census_reset_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.resetCensusIdentity()
                                showReset = false
                            }) {
                                Text(stringResource(R.string.developer_settings_census_reset_confirm))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showReset = false }) {
                                Text(stringResource(R.string.developer_settings_census_reset_cancel))
                            }
                        },
                    )
                }
            }
        }
    }
}
