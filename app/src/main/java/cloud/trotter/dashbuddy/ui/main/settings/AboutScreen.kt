package cloud.trotter.dashbuddy.ui.main.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.feature.settings.SettingsMenuViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: SettingsMenuViewModel = hiltViewModel()
) {
    val clicks by viewModel.versionClickCount.collectAsStateWithLifecycle()
    val isDevUnlocked by viewModel.isDevModeUnlocked.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    val devModeCountdownTemplate = stringResource(R.string.about_screen_dev_mode_countdown)
    val devModeEnabledText = stringResource(R.string.about_screen_dev_mode_enabled)

    // Hold a reference to the toast so we can cancel it instantly on rapid clicks
    var currentToast by remember { mutableStateOf<Toast?>(null) }

    LaunchedEffect(clicks) {
        if (clicks in 3..6 && !isDevUnlocked) {
            currentToast?.cancel()
            currentToast = Toast.makeText(
                context,
                devModeCountdownTemplate.format(7 - clicks),
                Toast.LENGTH_SHORT
            )
            currentToast?.show()
        } else if (clicks == 7 && !isDevUnlocked) {
            currentToast?.cancel()
            currentToast = Toast.makeText(context, devModeEnabledText, Toast.LENGTH_LONG)
            currentToast?.show()
            viewModel.unlockDeveloperMode()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_screen_title)) },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // The Clickable Version Text
            Column(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable { viewModel.onVersionClicked() }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.about_screen_version_format, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.about_screen_build_format, BuildConfig.VERSION_CODE.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.text3
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 32.dp))
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.about_screen_developer_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_screen_developer_name),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.about_screen_developer_email),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}