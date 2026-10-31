package cloud.trotter.log.gig.ui.main.setup.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.feature.settings.R as SettingsR

/**
 * #1457 — step 2, the accessibility service's own disclosure screen (Play's prominent disclosure for
 * an app that is not an accessibility tool, #170 / #1413): what the service reads and why, what is
 * dropped and what is saved as codes, screenshots, where it all happens and what can leave the phone,
 * and when it taps. Nothing here grants anything: Allow ([onAllow]) only opens Android's
 * Accessibility settings, and Don't allow ([onDontAllow]) puts this step off and records nothing.
 * Stateless.
 */
@Composable
fun AccessibilityDisclosureCard(
    onAllow: () -> Unit,
    onDontAllow: () -> Unit,
) {
    PermissionStepColumn {
        PermissionStepHeader(
            stepNumber = PermissionType.Accessibility.stepNumber,
            iconRes = R.drawable.ic_accessibility,
            titleRes = PermissionType.Accessibility.titleRes,
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            R.string.perm_a11y_use,
            R.string.perm_a11y_data,
            R.string.perm_a11y_screenshots,
            R.string.perm_a11y_where,
            R.string.perm_a11y_taps,
        ).forEachIndexed { index, paragraph ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            PermissionBodyText(stringResource(paragraph))
        }
        PermissionWithoutIt(stringResource(PermissionType.Accessibility.withoutRes))
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onDontAllow,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(SettingsR.string.event_receipt_deny)) }
            Button(
                onClick = onAllow,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(SettingsR.string.event_receipt_allow)) }
        }
        Spacer(Modifier.height(16.dp))
    }
}
