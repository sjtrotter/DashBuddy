package cloud.trotter.dashbuddy.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * A tappable one-line disclosure with a caret — the shared shape behind the Money tab's car-cost line,
 * the Time tab's measurement notes, and (#1024) the screen-level [HowNumbersWorkFooter].
 *
 * Not a full [cloud.trotter.dashbuddy.core.designsystem.component.AppAccordion]: that component owns a
 * card surface of its own, and this sits INSIDE a card (or under one) as a footnote, not as a sibling
 * panel.
 *
 * It lived in `ui/main/analytics/PayMixCard.kt` until #1024 gave it a consumer outside the analytics
 * hub; a shared shape whose home is one card of one tab is a divergence waiting to happen, so it moved
 * to the shared components package rather than being reached for across surfaces (Principle 5). Stateless
 * by design — the caller owns `expanded`, so a screen can decide whether two disclosures may be open at
 * once.
 */
@Composable
internal fun DisclosureRow(text: String, expanded: Boolean, onToggle: () -> Unit) {
    val c = AppTheme.colors
    val clickLabel = stringResource(
        if (expanded) R.string.money_tab_disclosure_collapse else R.string.money_tab_disclosure_expand,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel, role = Role.Button) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (expanded) "⌃" else "⌄",
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}
