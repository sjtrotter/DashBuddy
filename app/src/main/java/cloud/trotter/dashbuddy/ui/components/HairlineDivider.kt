package cloud.trotter.dashbuddy.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * The row-list separator: a hairline with equal breathing room above and below.
 *
 * #1024's "fewer, denser containers" turns cards into row lists, and a row list is only readable if
 * its separators are identical — so the `Spacer / HorizontalDivider(c.line) / Spacer` triple that
 * appeared five times across the Money tab in one pass is one helper (review F10). The [gap] is the
 * space on EACH side, not the total.
 *
 * **Module boundary:** `:feature:dashboard` carries its own module-internal equivalent for Home
 * (#1024 part 3). That is the honest-deps doctrine, not an oversight — a feature module cannot reach
 * an `:app` owner, and inverting a three-line divider into `:core:designsystem` would buy less than
 * the dependency costs. If a third consumer appears, that is the moment to promote it.
 */
@Composable
internal fun HairlineDivider(gap: Dp = 10.dp) {
    Spacer(Modifier.height(gap))
    HorizontalDivider(color = AppTheme.colors.line)
    Spacer(Modifier.height(gap))
}
