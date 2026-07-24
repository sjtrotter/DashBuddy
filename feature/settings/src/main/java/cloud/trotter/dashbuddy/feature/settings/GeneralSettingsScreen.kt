package cloud.trotter.dashbuddy.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import cloud.trotter.dashbuddy.feature.settings.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented

/**
 * The spoken-offer (TTS) language choices (#428 Half B). [tag] is the stored BCP-47 override
 * (`null` ⇒ follow the system locale); [labelRes] is the row's option label. Kept here, not in
 * `:domain`, because the tag↔label mapping is a UI concern.
 */
private enum class TtsLangOption(val tag: String?, val labelRes: Int) {
    SYSTEM(null, R.string.tts_language_option_system),
    ENGLISH("en", R.string.tts_language_option_english),
    SPANISH("es", R.string.tts_language_option_spanish);

    companion object {
        fun fromTag(tag: String?): TtsLangOption = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/**
 * General app settings. The driving / glance-mode toggle (#318) and the spoken-offer language
 * override (#428 Half B); Theme and Pro Mode land here later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsMenuViewModel = hiltViewModel()
) {
    val glanceMode by viewModel.glanceMode.collectAsStateWithLifecycle(initialValue = false)
    // #428 Half B: reflect the persisted override reactively; null ⇒ System default.
    val ttsLanguageTag by viewModel.ttsLanguageTag.collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.general_settings_title)) },
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
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.general_settings_section_driving),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            SwitchRow(
                label = stringResource(R.string.general_settings_glance_mode_label),
                subtitle = stringResource(R.string.general_settings_glance_mode_subtitle),
                checked = glanceMode,
                onCheckedChange = { viewModel.setGlanceMode(it) }
            )

            // --- Voice: spoken-offer language (#428 Half B) ---
            Text(
                stringResource(R.string.general_settings_section_voice),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
            )
            Text(
                stringResource(R.string.general_settings_tts_language_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.general_settings_tts_language_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // Pair each option with its resolved label so selection maps back to the tag without
            // re-matching localized text through a Context.
            val labeledOptions = TtsLangOption.entries.map { it to stringResource(it.labelRes) }
            val selectedOption = TtsLangOption.fromTag(ttsLanguageTag)
            AppSegmented(
                options = labeledOptions.map { it.second },
                selected = labeledOptions.first { it.first == selectedOption }.second,
                onSelect = { label ->
                    val chosen = labeledOptions.first { it.second == label }.first
                    viewModel.setTtsLanguage(chosen.tag)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
