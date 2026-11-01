package cloud.trotter.log.gig.feature.setup.wizard.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.feature.setup.R

/**
 * The setup wizard's title bar (#1447): "Setup" with a back arrow and Skip, then the step line and
 * the progress bar under it. The wizard sits below a tab, so it has a back button on every step;
 * [onBack] is the same handler system Back runs (the host decides what a step-1 Back means).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardTopBar(
    currentStep: Int,
    totalSteps: Int,
    onBack: () -> Unit,
    onSkip: () -> Unit
) {
    // Animates the progress bar so it smoothly slides as they swipe
    val progress by animateFloatAsState(
        targetValue = (currentStep + 1) / totalSteps.toFloat(),
        label = "progress"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text(stringResource(R.string.wizard_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_content_desc_back),
                    )
                }
            },
            actions = {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.wizard_top_bar_skip_setup))
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.wizard_top_bar_step_of_total, currentStep + 1, totalSteps),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}
