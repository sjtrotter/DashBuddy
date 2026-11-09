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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.feature.settings.PrivacyNoticeLink
import cloud.trotter.log.gig.feature.settings.R as SettingsR

/**
 * #1457 — the accessibility step (step 2, or 1 in a build with no Screen-events step), the service's own
 * disclosure screen (Play's prominent disclosure for an app that is not an accessibility tool, #170 /
 * #1413): which apps the service reads and why, what is dropped and what is saved as codes, screenshots and
 * where they go, where it all happens and what can leave the phone, when it taps, and a link to gig.log's
 * Privacy page ([onOpenPrivacy]). #170: the apps are named from the registry; a build that offers Screen events adds that
 * sentence, and a build that does not declare itself an accessibility tool (the Play build) says which offer
 * pop-up it can't read ([facts]). Nothing here grants anything: Allow ([onAllow]) only opens Android's
 * Accessibility settings, and Don't allow ([onDontAllow]) puts this step off and records nothing.
 * Stateless.
 */
@Composable
fun AccessibilityDisclosureCard(
    stepNumber: Int,
    stepCount: Int,
    facts: AccessibilityDisclosureFacts,
    onOpenPrivacy: () -> Unit,
    onAllow: () -> Unit,
    onDontAllow: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val pair = stringResource(R.string.perm_a11y_names_pair)
    val paragraphs = facts.paragraphs().map { paragraph ->
        if (paragraph.names == null) {
            stringResource(paragraph.res)
        } else {
            stringResource(paragraph.res, joinAppNames(paragraph.names, pair, locale))
        }
    }
    PermissionStepColumn {
        PermissionStepHeader(
            stepNumber = stepNumber,
            stepCount = stepCount,
            iconRes = R.drawable.ic_accessibility,
            titleRes = PermissionType.Accessibility.titleRes,
        )
        Spacer(Modifier.height(8.dp))
        paragraphs.forEachIndexed { index, paragraph ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            PermissionBodyText(paragraph)
        }
        PrivacyNoticeLink(onClick = onOpenPrivacy)
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
