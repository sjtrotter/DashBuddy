package cloud.trotter.dashbuddy.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.domain.model.bubble.BubbleSessionMode
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
 * Developer Options — the field-experiment switches. Today: the bubble's session-presentation mode
 * (#867), which the developer live-tests across a multi-app dash; whichever presentation wins
 * becomes the shipped default and the others get deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsMenuViewModel = hiltViewModel(),
) {
    val sessionMode by viewModel.bubbleSessionMode
        .collectAsStateWithLifecycle(initialValue = BubbleSessionMode.Default)

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
        }
    }
}
