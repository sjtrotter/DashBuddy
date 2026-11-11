package cloud.trotter.log.gig.feature.setup.wizard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.feature.setup.R

@Composable
fun WizardBottomBar(
    showBack: Boolean,
    isLastStep: Boolean,
    onBackClick: () -> Unit,
    onNextClick: () -> Unit,
    /** A save is running (#1473): both buttons are off and the save button shows progress. */
    saving: Boolean = false,
) {
    // #1447 N2: the footer owns its insets — the shell's frame takes none, so Next must clear the
    // gesture bar, 3-button navigation and a side cutout itself.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.navigationBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(16.dp),
        horizontalArrangement = if (showBack) Arrangement.SpaceBetween else Arrangement.End
    ) {
        if (showBack) {
            OutlinedButton(onClick = onBackClick, enabled = !saving) {
                Text(stringResource(R.string.wizard_bottom_bar_back))
            }
        }

        Button(onClick = onNextClick, enabled = !saving) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = when {
                    saving -> stringResource(R.string.wizard_bottom_bar_saving)
                    isLastStep -> stringResource(R.string.wizard_bottom_bar_finish_and_save)
                    else -> stringResource(R.string.wizard_bottom_bar_next)
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}